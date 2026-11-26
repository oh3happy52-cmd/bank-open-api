package com.example.bank_open_api.transfer.api;

// [무엇] 오픈 API 이체 요청 본문(JSON)
// [역할] {fromAccountNumber, toAccountNumber, amount} 를 받아 형식을 검사한다(@Valid). 어기면 400 INVALID_REQUEST
//        - 계좌번호: 영문·숫자·하이픈 1~20자 (계좌 조회와 같은 규칙. bank_core account_number가 VARCHAR(20))
//        - 금액: 1원 이상 (최대 금액 10억 원은 bank_core 규칙이라 bank_core가 400으로 판단 → 그대로 전달)
// [누가 쓰나] TransferController

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record TransferRequest(
        @NotBlank(message = "보내는 계좌번호를 입력하세요")
        @Pattern(regexp = "[A-Za-z0-9-]{1,20}", message = "계좌번호 형식이 올바르지 않습니다")
        String fromAccountNumber,

        @NotBlank(message = "받는 계좌번호를 입력하세요")
        @Pattern(regexp = "[A-Za-z0-9-]{1,20}", message = "계좌번호 형식이 올바르지 않습니다")
        String toAccountNumber,

        // Long: 칸이 빠지면 null → @NotNull로 400 (long이면 0으로 채워져 "금액 0"과 구분이 안 됨)
        @NotNull(message = "금액을 입력하세요")
        @Min(value = 1, message = "금액은 1원 이상이어야 합니다")
        Long amount
) {
}
