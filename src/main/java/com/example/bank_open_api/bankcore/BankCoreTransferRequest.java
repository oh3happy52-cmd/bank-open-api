package com.example.bank_open_api.bankcore;

// [무엇] bank_core 이체 요청 본문 (bank_core의 TransferRequest와 같은 칸 이름)
// [역할] POST /api/v1/transfers 로 보낼 JSON {fromAccountNumber, toAccountNumber, amount}
//        Idempotency-Key는 본문이 아니라 헤더로 보낸다 (BankCoreClient)
// [누가 쓰나] BankCoreClient.transfer

record BankCoreTransferRequest(
        String fromAccountNumber,   // 보내는 계좌 (연동 회원의 계좌여야 함. 아니면 bank_core가 403)
        String toAccountNumber,     // 받는 계좌 (없으면 bank_core가 404)
        long amount                 // 금액 (원)
) {
}
