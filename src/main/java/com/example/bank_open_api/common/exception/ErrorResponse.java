package com.example.bank_open_api.common.exception;

// [무엇] 오류 응답 본문(JSON)
// [역할] 모든 오류를 {"code": "...", "message": "..."} 같은 모양으로 내보냄 (bank_core와 같은 형식)
//        code는 프로그램이 구분하는 이름, message는 사람이 읽는 설명
// [누가 쓰나] GlobalExceptionHandler

public record ErrorResponse(String code, String message) {

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(errorCode.name(), message);
    }
}
