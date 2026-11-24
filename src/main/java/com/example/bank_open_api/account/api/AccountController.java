package com.example.bank_open_api.account.api;

// [무엇] 오픈 API 계좌 조회 창구
// [역할] 핀테크 앱의 계좌 조회 요청을 받아 계좌번호 형식을 검사한 뒤 bank_core에 전달(BankCoreClient)하고,
//        결과를 우리 응답 모양(AccountResponse)으로 바꿔 돌려준다
// [누가 쓰나] 핀테크 앱(서버). X-API-Key 헤더가 있어야 호출 가능 (OpenApiSecurityConfig + ApiKeyAuthenticationFilter)
//
// 예) curl -H "X-API-Key: boa_live_..." http://localhost:8081/open/v1/accounts/100-0001

import com.example.bank_open_api.bankcore.BankCoreClient;
import com.example.bank_open_api.common.exception.BusinessException;
import com.example.bank_open_api.common.exception.ErrorCode;
import com.example.bank_open_api.common.security.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

@RestController
@RequestMapping("/open/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

    // 계좌번호 형식: 영문·숫자·하이픈 1~20글자 (bank_core account_number 열이 VARCHAR(20))
    // 이상한 글자가 bank_core까지 가지 않게 여기서 먼저 막는다
    private static final Pattern ACCOUNT_NUMBER = Pattern.compile("[A-Za-z0-9-]{1,20}");

    private final BankCoreClient bankCoreClient;

    /**
     * 계좌 조회
     * GET /open/v1/accounts/{accountNumber}
     * - 200: {accountNumber, status, balance}
     * - 400: INVALID_REQUEST (계좌번호 형식 오류)
     * - 401: INVALID_API_KEY (키 없음·없는 키·폐기된 키)
     * - 404: ACCOUNT_NOT_FOUND (없는 계좌, 또는 연동 회원의 계좌가 아님 — 둘을 구분하지 않음)
     * - 502: BANK_CORE_UNAVAILABLE (bank_core 꺼짐·5xx·연동 계정 문제)
     * - 504: BANK_CORE_TIMEOUT (bank_core 응답 지연)
     */
    @GetMapping("/{accountNumber}")
    public AccountResponse getAccount(@AuthenticationPrincipal ApiClient client,
                                      @PathVariable String accountNumber) {
        if (!ACCOUNT_NUMBER.matcher(accountNumber).matches()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "계좌번호 형식 오류");
        }
        return AccountResponse.from(bankCoreClient.getAccount(client.appId(), accountNumber));
    }
}
