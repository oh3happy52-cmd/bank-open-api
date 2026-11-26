package com.example.bank_open_api.bankcore;

// [무엇] bank_core 호출 도구 (계좌 조회, 이체)
// [역할] bank_core API를 "연동 전용 회원" 토큰으로 부르고, 결과를 우리 쪽 규칙으로 바꾼다
//        두 호출 모두 401(토큰 거절)이면 토큰을 새로 받아 딱 한 번만 재시도한다 (withTokenRetry)
//
//   ① 계좌 조회 GET /api/v1/accounts/{accountNumber}
//          200          → BankCoreAccount
//          403, 404     → 404 ACCOUNT_NOT_FOUND (둘을 구분하지 않아 계좌 존재 여부를 숨김)
//          5xx·그 밖    → 502 BANK_CORE_UNAVAILABLE
//          꺼짐·연결 실패 → 502 BANK_CORE_UNAVAILABLE / 제한 시간 초과 → 504 BANK_CORE_TIMEOUT
//
//   ② 이체 POST /api/v1/transfers (헤더 Idempotency-Key)
//          201          → BankCoreTransfer (같은 키로 다시 보내면 bank_core가 처음 결과를 그대로 줌)
//          400          → 400, bank_core code를 우리 같은 이름의 code로 (SAME_ACCOUNT 등)
//          403, 404     → 404 ACCOUNT_NOT_FOUND (출금 계좌가 연동 회원 것이 아님 / 받는 계좌 없음 → 같은 응답)
//          409          → 409 DUPLICATE_REQUEST (같은 키가 처리 중)
//          422          → 422, bank_core code를 우리 같은 이름의 code로 (INSUFFICIENT_BALANCE 등)
//          5xx          → 502 BANK_CORE_UNAVAILABLE
//          그 밖        → 502 BANK_CORE_UNAVAILABLE + ERROR 로그 (약속에 없는 응답)
//          꺼짐·연결 실패 → 502 / 제한 시간 초과 → 504 BANK_CORE_TIMEOUT + "처리 결과를 알 수 없습니다..." 안내
//      bank_core의 오류 메시지 원문은 쓰지 않는다 (계좌번호가 섞여 있을 수 있음). 메시지는 항상 우리 ErrorCode 문구
// [누가 쓰나] AccountController(계좌 조회), TransferService(이체)
//
// ── 로그 규칙 ──
//   남기는 것: appId, 계좌번호 끝 4자리, bank_core 응답 상태 코드(또는 TIMEOUT/CONNECTION_FAILED), 걸린 시간,
//             이체의 원래 Idempotency-Key(비밀값이 아님), 금액
//   남기지 않는 것: 계좌번호 전체, bank_core 토큰, 비밀번호, API 키 원문, bank_core 오류 메시지 원문

import com.example.bank_open_api.common.exception.BusinessException;
import com.example.bank_open_api.common.exception.ErrorCode;
import com.example.bank_open_api.common.logging.LogMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

@Slf4j
@Component
public class BankCoreClient {

    // 이체가 제한 시간을 넘겼을 때의 안내. bank_core가 이미 처리했을 수도, 안 했을 수도 있다
    // → 같은 키로 다시 보내면 처리됐으면 처음 결과를, 아니면 새로 처리해 준다 (돈이 두 번 나가지 않음)
    static final String TRANSFER_TIMEOUT_MESSAGE = "처리 결과를 알 수 없습니다. 같은 Idempotency-Key로 다시 요청하세요";

    // bank_core 이체 오류 code → 우리 ErrorCode (이름이 같은 것끼리 짝지음. 메시지는 우리 문구)
    private static final Map<String, ErrorCode> TRANSFER_400_CODES = Map.of(
            "INVALID_REQUEST", ErrorCode.INVALID_REQUEST,
            "INVALID_AMOUNT", ErrorCode.INVALID_AMOUNT,
            "SAME_ACCOUNT", ErrorCode.SAME_ACCOUNT,
            "MISSING_IDEMPOTENCY_KEY", ErrorCode.MISSING_IDEMPOTENCY_KEY,
            "INVALID_IDEMPOTENCY_KEY", ErrorCode.INVALID_IDEMPOTENCY_KEY);
    private static final Map<String, ErrorCode> TRANSFER_422_CODES = Map.of(
            "INSUFFICIENT_BALANCE", ErrorCode.INSUFFICIENT_BALANCE,
            "ACCOUNT_NOT_ACTIVE", ErrorCode.ACCOUNT_NOT_ACTIVE,
            "IDEMPOTENCY_KEY_REUSED", ErrorCode.IDEMPOTENCY_KEY_REUSED,
            "SYSTEM_ACCOUNT_NOT_ALLOWED", ErrorCode.SYSTEM_ACCOUNT_NOT_ALLOWED);

    private final RestClient restClient;
    private final BankCoreTokenProvider tokenProvider;

    public BankCoreClient(@Qualifier(BankCoreConfig.REST_CLIENT) RestClient restClient,
                          BankCoreTokenProvider tokenProvider) {
        this.restClient = restClient;
        this.tokenProvider = tokenProvider;
    }

    // 한 번 호출한 결과: 값을 받았거나, 401(토큰 거절)이었거나
    private record Attempt<T>(T value, boolean unauthorized) {
        static <T> Attempt<T> ok(T value) {
            return new Attempt<>(value, false);
        }

        static <T> Attempt<T> rejected() {
            return new Attempt<>(null, true);
        }
    }

    /**
     * 토큰을 붙여 한 번 부르고, 401이면 새 토큰으로 딱 한 번만 다시 부른다 (계좌 조회·이체 공통)
     * 이체의 경우 call 안에서 쓰는 Idempotency-Key가 바뀌지 않으므로 "같은 키로" 재시도된다.
     * (bank_core는 토큰 검사를 이체 처리보다 먼저 하므로, 401이면 돈은 옮겨지지 않은 상태)
     *
     * @param logContext 새 토큰도 거절될 때 남길 로그 내용 (예: "appId=1, accountLast4=0001")
     */
    private <T> T withTokenRetry(Supplier<String> logContext, Function<String, Attempt<T>> call) {
        String token = tokenProvider.getToken();
        Attempt<T> attempt = call.apply(token);
        if (!attempt.unauthorized()) {
            return attempt.value();
        }

        // 401: 토큰이 만료됐거나 bank_core가 재시작되며 비밀 키가 바뀐 경우 등
        String renewed = tokenProvider.renewAfterRejected(token);
        Attempt<T> retry = call.apply(renewed);
        if (!retry.unauthorized()) {
            return retry.value();
        }

        // 방금 받은 새 토큰도 거절 → 연동 설정 문제(bank_core 쪽 회원 상태 등). 계속 재시도하지 않는다
        log.error("bank_core가 새 토큰도 거절함: {}", logContext.get());
        throw new BusinessException(ErrorCode.BANK_CORE_UNAVAILABLE);
    }

    // ───────────────────────── ① 계좌 조회 ─────────────────────────

    /**
     * 계좌 조회
     *
     * @param appId         요청한 핀테크 앱 번호 (로그용)
     * @param accountNumber 조회할 계좌번호 (형식 검사는 컨트롤러에서 끝난 값)
     */
    public BankCoreAccount getAccount(Long appId, String accountNumber) {
        return withTokenRetry(
                () -> "appId=" + appId + ", accountLast4=" + LogMasking.last4(accountNumber),
                token -> callAccount(appId, accountNumber, token));
    }

    // bank_core 계좌 조회 1회
    private Attempt<BankCoreAccount> callAccount(Long appId, String accountNumber, String token) {
        long start = System.nanoTime();
        try {
            return restClient.get()
                    // {accountNumber} 자리 채우기: RestClient가 주소에 맞게 안전하게 인코딩한다
                    .uri("/api/v1/accounts/{accountNumber}", accountNumber)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        long elapsed = BankCoreFailures.elapsedMillis(start);

                        if (status == 200) {
                            BankCoreAccount body = response.bodyTo(BankCoreAccount.class);
                            if (body == null || body.accountNumber() == null || body.balance() == null) {
                                log.warn("bank_core 계좌 조회 응답 형식 이상: appId={}, accountLast4={}, status=200, elapsedMs={}",
                                        appId, LogMasking.last4(accountNumber), elapsed);
                                throw new BusinessException(ErrorCode.BANK_CORE_UNAVAILABLE);
                            }
                            log.info("bank_core 계좌 조회: appId={}, accountLast4={}, status={}, elapsedMs={}",
                                    appId, LogMasking.last4(accountNumber), status, elapsed);
                            return Attempt.ok(body);
                        }

                        if (status == 401) {
                            log.info("bank_core 계좌 조회: appId={}, accountLast4={}, status={}, elapsedMs={}",
                                    appId, LogMasking.last4(accountNumber), status, elapsed);
                            return Attempt.rejected();
                        }

                        if (status == 403 || status == 404) {
                            // 403(연동 회원의 계좌가 아님·없는 계좌)과 404를 같은 응답으로 → 계좌 존재 여부를 숨김
                            log.info("bank_core 계좌 조회: appId={}, accountLast4={}, status={}, elapsedMs={}",
                                    appId, LogMasking.last4(accountNumber), status, elapsed);
                            throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND);
                        }

                        // 5xx, 그 밖의 예상 못 한 상태 코드
                        log.warn("bank_core 계좌 조회 실패: appId={}, accountLast4={}, status={}, elapsedMs={}",
                                appId, LogMasking.last4(accountNumber), status, elapsed);
                        throw new BusinessException(ErrorCode.BANK_CORE_UNAVAILABLE);
                    });
        } catch (ResourceAccessException e) {
            // 응답을 아예 못 받음 (꺼짐·연결 실패 → 502, 제한 시간 초과 → 504)
            // e.getMessage()에는 요청 주소 전체(= 계좌번호 전체)가 들어 있어서 찍지 않는다
            log.warn("bank_core 계좌 조회 실패: appId={}, accountLast4={}, status={}, elapsedMs={}",
                    appId, LogMasking.last4(accountNumber), BankCoreFailures.label(e), BankCoreFailures.elapsedMillis(start));
            throw BankCoreFailures.toBusinessException(e);
        }
    }

    // ───────────────────────── ② 이체 ─────────────────────────

    /**
     * 이체
     *
     * @param appId                 요청한 핀테크 앱 번호 (로그용)
     * @param originalKey           앱이 보낸 원래 Idempotency-Key (로그용)
     * @param bankCoreKey           bank_core에 보낼 키 ("app-{appId}:{원래 키}" 형태. BankCoreIdempotencyKey가 만듦)
     * @param fromAccountNumber     보내는 계좌 (형식 검사는 컨트롤러에서 끝난 값)
     * @param toAccountNumber       받는 계좌
     * @param amount                금액 (1 이상)
     */
    public BankCoreTransfer transfer(Long appId, String originalKey, String bankCoreKey,
                                     String fromAccountNumber, String toAccountNumber, long amount) {
        BankCoreTransferRequest body = new BankCoreTransferRequest(fromAccountNumber, toAccountNumber, amount);
        TransferLog transferLog = new TransferLog(appId, originalKey, fromAccountNumber, toAccountNumber, amount);
        return withTokenRetry(transferLog::describe, token -> callTransfer(transferLog, bankCoreKey, body, token));
    }

    // 이체 로그에 반복해서 쓰는 값 (계좌번호는 끝 4자리만 꺼내 쓴다)
    private record TransferLog(Long appId, String idempotencyKey, String from, String to, long amount) {
        String describe() {
            return "appId=" + appId + ", idempotencyKey=" + idempotencyKey
                    + ", fromLast4=" + LogMasking.last4(from) + ", toLast4=" + LogMasking.last4(to)
                    + ", amount=" + amount;
        }
    }

    // bank_core 이체 1회
    private Attempt<BankCoreTransfer> callTransfer(TransferLog transferLog, String bankCoreKey,
                                                   BankCoreTransferRequest body, String token) {
        long start = System.nanoTime();
        try {
            return restClient.post()
                    .uri("/api/v1/transfers")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header("Idempotency-Key", bankCoreKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        long elapsed = BankCoreFailures.elapsedMillis(start);

                        if (status == 201) {
                            BankCoreTransfer result = response.bodyTo(BankCoreTransfer.class);
                            if (result == null || result.transferId() == null
                                    || result.amount() == null || result.status() == null) {
                                // 돈은 옮겨졌을 수 있다 → ERROR. 같은 키로 다시 보내면 처음 결과를 받을 수 있다
                                log.error("bank_core 이체 응답 형식 이상: {}, status=201, elapsedMs={}",
                                        transferLog.describe(), elapsed);
                                throw new BusinessException(ErrorCode.BANK_CORE_UNAVAILABLE);
                            }
                            log.info("bank_core 이체: {}, status={}, transferId={}, elapsedMs={}",
                                    transferLog.describe(), status, result.transferId(), elapsed);
                            return Attempt.ok(result);
                        }

                        if (status == 401) {
                            log.info("bank_core 이체: {}, status={}, elapsedMs={}", transferLog.describe(), status, elapsed);
                            return Attempt.rejected();
                        }

                        // 그 밖은 모두 실패 → 오류 code만 읽고(메시지는 버림) 우리 오류로 바꿔 던진다
                        String code = readErrorCode(response);
                        throw toTransferFailure(transferLog, status, code, elapsed);
                    });
        } catch (ResourceAccessException e) {
            // 응답을 아예 못 받음
            log.warn("bank_core 이체 실패: {}, status={}, elapsedMs={}",
                    transferLog.describe(), BankCoreFailures.label(e), BankCoreFailures.elapsedMillis(start));
            if (BankCoreFailures.isTimeout(e)) {
                // 요청은 bank_core에 도착해 처리됐을 수도 있다 → "결과 모름, 같은 키로 다시"를 안내
                throw BusinessException.withMessage(ErrorCode.BANK_CORE_TIMEOUT, TRANSFER_TIMEOUT_MESSAGE);
            }
            throw new BusinessException(ErrorCode.BANK_CORE_UNAVAILABLE);
        }
    }

    // bank_core 이체 실패 응답 → 우리 BusinessException
    private BusinessException toTransferFailure(TransferLog transferLog, int status, String code, long elapsed) {
        ErrorCode mapped;
        if (status == 400) {
            mapped = knownOrDefault(transferLog, status, code, TRANSFER_400_CODES, ErrorCode.INVALID_REQUEST);
        } else if (status == 403 || status == 404) {
            // 403: 출금 계좌가 연동 회원 것이 아님(없는 계좌 포함) / 404: 받는 계좌 없음 → 같은 404, 같은 본문
            mapped = ErrorCode.ACCOUNT_NOT_FOUND;
        } else if (status == 409) {
            mapped = ErrorCode.DUPLICATE_REQUEST;
        } else if (status == 422) {
            mapped = knownOrDefault(transferLog, status, code, TRANSFER_422_CODES, ErrorCode.TRANSFER_NOT_PROCESSABLE);
        } else if (status >= 500) {
            log.warn("bank_core 이체 실패: {}, status={}, elapsedMs={}", transferLog.describe(), status, elapsed);
            return new BusinessException(ErrorCode.BANK_CORE_UNAVAILABLE);
        } else {
            // 약속에 없는 상태 코드 (예: 200, 302, 405, 429) → bank_core가 바뀌었을 수 있으니 운영자가 바로 봐야 함
            log.error("bank_core 이체 응답이 약속과 다름: {}, status={}, code={}, elapsedMs={}",
                    transferLog.describe(), status, code, elapsed);
            return new BusinessException(ErrorCode.BANK_CORE_UNAVAILABLE);
        }
        log.info("bank_core 이체 거절: {}, status={}, code={}, elapsedMs={}", transferLog.describe(), status, code, elapsed);
        return new BusinessException(mapped);
    }

    // 아는 code면 같은 이름의 우리 ErrorCode, 모르는 code면 같은 상태의 기본 ErrorCode (+ WARN: 짝을 추가해야 함)
    private static ErrorCode knownOrDefault(TransferLog transferLog, int status, String code,
                                            Map<String, ErrorCode> known, ErrorCode fallback) {
        ErrorCode mapped = code == null ? null : known.get(code);
        if (mapped != null) {
            return mapped;
        }
        log.warn("bank_core 이체 오류 code를 모름(기본 code로 응답): {}, status={}, code={}",
                transferLog.describe(), status, code);
        return fallback;
    }

    // 오류 응답에서 code만 꺼낸다. 본문이 없거나 JSON이 아니면 null
    private static String readErrorCode(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            BankCoreError error = response.bodyTo(BankCoreError.class);
            return error == null ? null : error.code();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
