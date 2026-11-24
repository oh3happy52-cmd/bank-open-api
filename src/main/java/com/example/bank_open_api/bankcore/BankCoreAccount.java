package com.example.bank_open_api.bankcore;

// [무엇] bank_core 계좌 조회 응답 모양 (bank_core의 AccountResponse와 같은 칸 이름)
// [역할] bank_core가 보낸 JSON을 그대로 받아 두는 그릇. 이 객체는 우리 API 응답으로 직접 내보내지 않는다
//        → 컨트롤러가 필요한 칸만 골라 우리 응답(AccountResponse)으로 바꾼다
//          (bank_core가 나중에 칸을 추가해도 우리 API 응답이 저절로 바뀌거나 내부 정보가 새지 않게)
// [누가 쓰나] BankCoreClient(받기), AccountController(우리 응답으로 바꾸기)

public record BankCoreAccount(
        String accountNumber,   // 계좌번호 (예: 100-0001)
        String ownerName,       // 예금주 이름
        String accountType,     // CUSTOMER / SYSTEM (bank_core 내부 구분)
        String status,          // ACTIVE / FROZEN / CLOSED
        Long balance            // 잔액 (원). 칸이 빠진 응답을 알아채려고 long 대신 Long
) {
}
