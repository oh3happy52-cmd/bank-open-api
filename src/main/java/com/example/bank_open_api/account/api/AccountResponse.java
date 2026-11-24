package com.example.bank_open_api.account.api;

// [무엇] 오픈 API 계좌 조회 응답 본문(JSON)
// [역할] bank_core 응답(BankCoreAccount)에서 핀테크 앱에 필요한 칸만 골라 담는다
//        - 넣는 것: 계좌번호, 상태, 잔액
//        - 빼는 것: 예금주 이름(개인정보 최소화), 계좌 종류(CUSTOMER/SYSTEM은 bank_core 내부 구분)
//        bank_core 응답을 그대로 넘기지 않으므로, bank_core가 칸을 바꿔도 우리 API 약속은 그대로 유지된다
// [누가 쓰나] AccountController

import com.example.bank_open_api.bankcore.BankCoreAccount;

public record AccountResponse(
        String accountNumber,   // 계좌번호
        String status,          // ACTIVE / FROZEN / CLOSED
        long balance            // 잔액 (원 단위 정수. 돈은 오차가 생기는 double을 쓰지 않음)
) {

    public static AccountResponse from(BankCoreAccount account) {
        return new AccountResponse(account.accountNumber(), account.status(), account.balance());
    }
}
