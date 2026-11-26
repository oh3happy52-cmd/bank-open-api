package com.example.bank_open_api.transfer.api;

// [무엇] 오픈 API 이체 창구
// [역할] 핀테크 앱의 이체 요청을 받아 Idempotency-Key·본문 형식을 검사한 뒤 TransferService로 넘긴다
//        형식이 틀리면 여기서 400으로 끝나고 bank_core는 호출하지 않는다
// [누가 쓰나] 핀테크 앱(서버). X-API-Key 헤더 필요. 다른 /open/v1 API와 같은 필터를 거친다
//            (API 키 검사 → 사용량 기록(주소 틀 /open/v1/transfers) → 호출 제한)
//
// 예) curl -X POST http://localhost:8081/open/v1/transfers \
//       -H "X-API-Key: boa_live_..." -H "Idempotency-Key: order-20261127-0001" \
//       -H "Content-Type: application/json" \
//       -d '{"fromAccountNumber":"200-0001","toAccountNumber":"200-0002","amount":1000}'
//
// ── Idempotency-Key란? ──
//   "이 요청의 번호표". 응답을 못 받아(타임아웃 등) 다시 보낼 때 같은 번호표를 쓰면,
//   이미 처리된 이체는 다시 처리하지 않고 처음 결과(같은 transferId)를 돌려준다 → 돈이 두 번 나가지 않는다
//   새 이체마다 새 키를 만들어야 한다.

import com.example.bank_open_api.common.exception.BusinessException;
import com.example.bank_open_api.common.exception.ErrorCode;
import com.example.bank_open_api.common.security.ApiClient;
import com.example.bank_open_api.transfer.application.TransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

@RestController
@RequestMapping("/open/v1/transfers")
@RequiredArgsConstructor
public class TransferController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    // 영문·숫자·-·_ 1~64자. 그 밖의 글자(공백, 한글, ':', '~' 등)는 받지 않는다
    // ('~'를 막는 것이 BankCoreIdempotencyKey의 "긴 키 지문" 표시와 겹치지 않게 하는 전제)
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final TransferService transferService;

    /**
     * 이체
     * POST /open/v1/transfers
     * - 201: {transferId, fromAccountNumber, toAccountNumber, amount, status} (같은 키 재요청이면 처음 결과)
     * - 400: MISSING_IDEMPOTENCY_KEY, INVALID_IDEMPOTENCY_KEY, INVALID_REQUEST(본문 형식), SAME_ACCOUNT 등
     * - 401: INVALID_API_KEY
     * - 404: ACCOUNT_NOT_FOUND (출금 계좌가 연동 회원 것이 아님 / 받는 계좌 없음 — 구분하지 않음)
     * - 409: DUPLICATE_REQUEST (같은 키의 이체가 처리 중)
     * - 422: INSUFFICIENT_BALANCE, ACCOUNT_NOT_ACTIVE, IDEMPOTENCY_KEY_REUSED, SYSTEM_ACCOUNT_NOT_ALLOWED
     * - 429: RATE_LIMIT_EXCEEDED
     * - 502: BANK_CORE_UNAVAILABLE / 504: BANK_CORE_TIMEOUT (결과를 모름 → 같은 키로 다시 요청)
     */
    @PostMapping
    public ResponseEntity<TransferResponse> transfer(
            @AuthenticationPrincipal ApiClient client,
            // required = false: 헤더가 없을 때 Spring 기본 오류 대신 우리 code(MISSING_IDEMPOTENCY_KEY)로 응답하려고
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {

        validateIdempotencyKey(idempotencyKey);

        var result = transferService.transfer(client, idempotencyKey,
                request.fromAccountNumber(), request.toAccountNumber(), request.amount());
        return ResponseEntity.status(HttpStatus.CREATED).body(TransferResponse.from(result));
    }

    private static void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            throw new BusinessException(ErrorCode.MISSING_IDEMPOTENCY_KEY);
        }
        if (!IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new BusinessException(ErrorCode.INVALID_IDEMPOTENCY_KEY);
        }
    }
}
