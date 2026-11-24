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
    // /open/v1/** 에서 API 키가 "없음 / 없는 키 / 폐기된 키"인 경우를 구분하지 않고 이 하나로 응답
    // → 응답만 보고는 "예전에 진짜였던 키인지" 알 수 없게 한다 (원인은 서버 로그에만 남김)
    INVALID_API_KEY(HttpStatus.UNAUTHORIZED, "API 키가 없거나 올바르지 않습니다"),

    // 403: 누구인지는 알지만 권한이 없음 (인가 실패)
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다"),

    // 404: 찾는 대상이 없음
    // APP_NOT_FOUND는 "없는 앱"과 "남의 앱" 둘 다에 쓴다 (AppService 주석 참고)
    // → 두 경우의 응답이 똑같아야 하므로 메시지에 appId 같은 상세 정보를 덧붙이지 않는다
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 주소를 찾을 수 없습니다"),
    APP_NOT_FOUND(HttpStatus.NOT_FOUND, "앱을 찾을 수 없습니다"),
    // 내 앱이지만 그 앱에 없는 키 번호 (남의 앱이면 키 확인 전에 APP_NOT_FOUND가 먼저 나감)
    API_KEY_NOT_FOUND(HttpStatus.NOT_FOUND, "API 키를 찾을 수 없습니다"),

    // 405: 주소는 있지만 그 HTTP 메서드(GET/POST 등)는 지원하지 않음
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다"),

    // 409: 이미 있는 데이터와 충돌
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 가입된 이메일입니다"),
    DUPLICATE_APP_NAME(HttpStatus.CONFLICT, "이미 같은 이름의 앱이 있습니다"),
    // 앱당 ACTIVE 키는 키 교체용으로 최대 2개 (새 키 발급 → 서비스에 적용 → 옛 키 폐기)
    API_KEY_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "앱당 활성 API 키는 최대 2개입니다"),

    // 500: 예상하지 못한 서버 오류
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다");

    private final HttpStatus status;   // 응답에 쓸 HTTP 상태 코드
    private final String message;      // 사람이 읽는 기본 메시지
}
