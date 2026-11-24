package com.example.bank_open_api.bankcore;

// [무엇] bank_core 계좌 조회 호출 도구
// [역할] bank_core GET /api/v1/accounts/{accountNumber} 를 "연동 전용 회원" 토큰으로 부르고,
//        결과를 우리 쪽 규칙으로 바꾼다
//          200          → BankCoreAccount
//          401          → 토큰을 새로 받아 딱 한 번 재시도 (또 401이면 502)
//          403, 404     → 404 ACCOUNT_NOT_FOUND (둘을 구분하지 않아 계좌 존재 여부를 숨김)
//          5xx·그 밖    → 502 BANK_CORE_UNAVAILABLE
//          꺼짐·연결 실패 → 502 BANK_CORE_UNAVAILABLE
//          제한 시간 초과 → 504 BANK_CORE_TIMEOUT
// [누가 쓰나] AccountController (GET /open/v1/accounts/{accountNumber})
//
// ── 로그 규칙 ──
//   남기는 것: appId, 계좌번호 끝 4자리, bank_core 응답 상태 코드(또는 TIMEOUT/CONNECTION_FAILED), 걸린 시간
//   남기지 않는 것: 계좌번호 전체, bank_core 토큰, 비밀번호, API 키 원문

import com.example.bank_open_api.common.exception.BusinessException;
import com.example.bank_open_api.common.exception.ErrorCode;
import com.example.bank_open_api.common.logging.LogMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class BankCoreClient {

    private final RestClient restClient;
    private final BankCoreTokenProvider tokenProvider;

    public BankCoreClient(@Qualifier(BankCoreConfig.REST_CLIENT) RestClient restClient,
                          BankCoreTokenProvider tokenProvider) {
        this.restClient = restClient;
        this.tokenProvider = tokenProvider;
    }

    // 한 번 호출한 결과: 계좌를 받았거나, 401(토큰 거절)이었거나
    private record Attempt(BankCoreAccount account, boolean unauthorized) {
        static Attempt ok(BankCoreAccount account) {
            return new Attempt(account, false);
        }

        static Attempt rejected() {
            return new Attempt(null, true);
        }
    }

    /**
     * 계좌 조회
     *
     * @param appId         요청한 핀테크 앱 번호 (로그용)
     * @param accountNumber 조회할 계좌번호 (형식 검사는 컨트롤러에서 끝난 값)
     */
    public BankCoreAccount getAccount(Long appId, String accountNumber) {
        String token = tokenProvider.getToken();
        Attempt attempt = callAccount(appId, accountNumber, token);
        if (!attempt.unauthorized()) {
            return attempt.account();
        }

        // 401: 토큰이 만료됐거나 bank_core가 재시작되며 비밀 키가 바뀐 경우 등 → 새 토큰으로 딱 한 번만 재시도
        String renewed = tokenProvider.renewAfterRejected(token);
        Attempt retry = callAccount(appId, accountNumber, renewed);
        if (!retry.unauthorized()) {
            return retry.account();
        }

        // 방금 받은 새 토큰도 거절 → 연동 설정 문제(bank_core 쪽 회원 상태 등). 계속 재시도하지 않는다
        log.error("bank_core가 새 토큰도 거절함: appId={}, accountLast4={}", appId, LogMasking.last4(accountNumber));
        throw new BusinessException(ErrorCode.BANK_CORE_UNAVAILABLE);
    }

    // bank_core 계좌 조회 1회
    private Attempt callAccount(Long appId, String accountNumber, String token) {
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
}
