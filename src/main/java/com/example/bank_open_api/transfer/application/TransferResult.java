package com.example.bank_open_api.transfer.application;

// [무엇] 이체 성공 결과 (우리 서비스 기준)
// [역할] bank_core 응답(BankCoreTransfer)을 우리 쪽 모양으로 바꾼 값.
//        bank_core 응답에는 계좌번호가 없어서, 요청한 보내는·받는 계좌를 함께 담는다
//        (같은 키 재요청은 bank_core가 "내용이 같을 때만" 처음 결과를 주므로 계좌·금액도 처음과 같다)
// [누가 쓰나] TransferService(만듦), TransferSuccessHandler(성공 처리), TransferController(응답으로 바꿈)

public record TransferResult(
        Long transferId,           // bank_core 이체 번호 (재요청이면 처음과 같은 번호)
        String fromAccountNumber,
        String toAccountNumber,
        long amount,
        String status              // COMPLETED 등 (bank_core 상태 그대로)
) {
}
