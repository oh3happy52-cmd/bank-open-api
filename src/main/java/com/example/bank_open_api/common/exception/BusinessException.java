package com.example.bank_open_api.common.exception;

// [무엇] 업무 규칙 위반 예외
// [역할] 이메일 중복, 로그인 실패 같은 규칙 위반을 ErrorCode와 함께 던짐
//        → GlobalExceptionHandler가 받아서 ErrorCode에 맞는 HTTP 상태(401/409 등)로 바꿈
// [누가 쓰나] 서비스 클래스들 (DeveloperService, AuthService)

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    // 어떤 오류인지 (HTTP 상태와 기본 메시지를 함께 들고 있음)
    private final ErrorCode errorCode;

    // 기본 메시지를 그대로 쓸 때
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    // 메시지에 정보를 덧붙일 때 (예: "...: 상세 내용")
    public BusinessException(ErrorCode errorCode, String detail) {
        super(errorCode.getMessage() + ": " + detail);
        this.errorCode = errorCode;
    }
}
