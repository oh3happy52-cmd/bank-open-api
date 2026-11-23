package com.example.bank_open_api.common.exception;

// [무엇] 오류 목록표 (enum)
// [역할] 이 서비스에서 일어날 수 있는 오류마다 "이름(code), HTTP 상태 코드, 기본 메시지"를 한곳에 정해 둠
//        (bank_core의 ErrorCode와 같은 구조)
// [누가 쓰나] BusinessException을 던지는 모든 곳, GlobalExceptionHandler, SecurityConfig(401/403 응답)

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 400: 요청 자체가 잘못됨 (이메일 형식 오류, 비밀번호 길이 부족 등)
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다"),

    // 401: 누구인지 확인되지 않음 (인증 실패)
    // 로그인 실패는 "이메일 없음"과 "비밀번호 틀림"을 구분하지 않고 이 하나로 응답
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다"),

    // 403: 누구인지는 알지만 권한이 없음 (인가 실패)
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다"),

    // 409: 이미 있는 데이터와 충돌
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 가입된 이메일입니다"),

    // 500: 예상하지 못한 서버 오류
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다");

    private final HttpStatus status;   // 응답에 쓸 HTTP 상태 코드
    private final String message;      // 사람이 읽는 기본 메시지
}
