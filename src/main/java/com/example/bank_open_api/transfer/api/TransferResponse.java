package com.example.bank_open_api.transfer.api;

// [무엇] 오픈 API 이체 성공 응답 본문(JSON)
// [역할] {transferId, fromAccountNumber, toAccountNumber, amount, status}
//        bank_core 응답을 그대로 넘기지 않고 우리 모양으로 만든다 (bank_core의 type 같은 내부 칸은 뺌)
//        → bank_core가 칸을 바꿔도 우리 API 약속은 그대로
// [누가 쓰나] TransferController

import com.example.bank_open_api.transfer.application.TransferResult;

public record TransferResponse(
        Long transferId,
        String fromAccountNumber,
        String toAccountNumber,
        long amount,
        String status
) {

    public static TransferResponse from(TransferResult result) {
        return new TransferResponse(result.transferId(), result.fromAccountNumber(), result.toAccountNumber(),
                result.amount(), result.status());
    }
}
