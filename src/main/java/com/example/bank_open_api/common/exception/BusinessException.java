package com.example.bank_open_api.common.exception;

// [무엇] 업무 규칙 위반 예외
// [역할] 이메일 중복, 로그인 실패 같은 규칙 위반을 ErrorCode와 함께 던짐
//        → GlobalExceptionHandler가 받아서 ErrorCode에 맞는 HTTP 상태(401/409 등)로 바꿈
// [누가 쓰나] 서비스 클래스들 (DeveloperService, AuthService, BankCoreClient 등)

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

    // 기본 메시지 대신 다른 메시지를 통째로 쓸 때 (withMessage에서만 사용)
    private BusinessException(String message, ErrorCode errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 같은 오류 코드인데 상황에 따라 안내 문구가 달라야 할 때
     * 예) 이체 제한 시간 초과: 코드는 BANK_CORE_TIMEOUT 그대로, 메시지는 "처리 결과를 알 수 없습니다. 같은 Idempotency-Key로 다시 요청하세요"
     * message에는 고정 문구만 넣는다 (요청 값·외부 응답을 넣지 않음 → 응답·로그에 계좌번호 등이 섞이지 않게)
     */
    public static BusinessException withMessage(ErrorCode errorCode, String message) {
        return new BusinessException(message, errorCode);
    }
}
