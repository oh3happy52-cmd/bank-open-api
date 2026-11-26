package com.example.bank_open_api.bankcore;

// [무엇] bank_core 이체 성공 응답 모양 (bank_core의 TransferResponse와 같은 칸 이름)
// [역할] bank_core가 보낸 JSON {transferId, type, amount, status}를 그대로 받아 두는 그릇.
//        이 객체는 우리 API 응답으로 직접 내보내지 않는다 → TransferService가 우리 응답 모양으로 바꾼다
//        (bank_core 응답에는 계좌번호가 없고, type 같은 내부 구분이 있다)
// [누가 쓰나] BankCoreClient(받기), TransferService(우리 결과로 바꾸기)
//
// 같은 Idempotency-Key로 다시 보내면 bank_core는 돈을 다시 옮기지 않고 "처음 결과"를 그대로 돌려준다
// → transferId가 처음과 같다

public record BankCoreTransfer(
        Long transferId,   // bank_core 이체 기록 번호. 칸이 빠진 응답을 알아채려고 long 대신 Long
        String type,       // TRANSFER (bank_core 내부 구분, 우리 응답에는 넣지 않음)
        Long amount,       // 금액 (원)
        String status      // PENDING / COMPLETED / FAILED
) {
}
