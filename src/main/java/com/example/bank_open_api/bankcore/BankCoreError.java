package com.example.bank_open_api.bankcore;

// [무엇] bank_core 오류 응답 본문 {code, message}
// [역할] 상태 코드만으로 부족할 때(400·409·422) code를 읽어 우리 오류 코드로 바꾸는 데 쓴다
//        message는 읽어도 우리 응답·로그에 쓰지 않는다
//        (bank_core 메시지에는 계좌번호가 섞여 있을 수 있다. 예: "계좌를 찾을 수 없습니다: 200-0002")
// [누가 쓰나] BankCoreClient

record BankCoreError(String code, String message) {
}
