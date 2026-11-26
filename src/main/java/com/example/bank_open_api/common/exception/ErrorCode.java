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
    // 사용량 통계 조회 기간 오류: 시작일이 종료일보다 늦거나, 기간이 31일을 넘음
    INVALID_DATE_RANGE(HttpStatus.BAD_REQUEST, "조회 기간이 올바르지 않습니다 (시작일 ≤ 종료일, 최대 31일)"),
    // 이체 (오픈 API /open/v1/transfers). bank_core와 같은 code 이름을 쓰지만 메시지는 우리 문구
    INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "금액이 올바르지 않습니다"),
    SAME_ACCOUNT(HttpStatus.BAD_REQUEST, "같은 계좌로는 이체할 수 없습니다"),
    MISSING_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "Idempotency-Key 헤더가 필요합니다"),
    INVALID_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "Idempotency-Key는 64자 이하의 영문·숫자·-·_ 만 쓸 수 있습니다"),

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
    // /open/v1/accounts: bank_core가 403(남의 계좌·없는 계좌)이든 404든 둘 다 이것으로 응답
    // → 응답만 보고 "그 계좌번호가 존재하는지" 알 수 없게 한다 (메시지에 계좌번호도 넣지 않음)
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "계좌를 찾을 수 없습니다"),

    // 405: 주소는 있지만 그 HTTP 메서드(GET/POST 등)는 지원하지 않음
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다"),

    // 409: 이미 있는 데이터와 충돌
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 가입된 이메일입니다"),
    DUPLICATE_APP_NAME(HttpStatus.CONFLICT, "이미 같은 이름의 앱이 있습니다"),
    // 앱당 ACTIVE 키는 키 교체용으로 최대 2개 (새 키 발급 → 서비스에 적용 → 옛 키 폐기)
    API_KEY_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "앱당 활성 API 키는 최대 2개입니다"),
    // 같은 Idempotency-Key의 이체가 아직 처리 중 (bank_core 409를 그대로 전달)
    DUPLICATE_REQUEST(HttpStatus.CONFLICT, "같은 Idempotency-Key의 요청이 처리 중입니다. 잠시 후 같은 키로 다시 요청하세요"),

    // 422: 형식은 맞지만 업무 규칙상 처리할 수 없음 (이체: bank_core 422를 같은 code로 전달)
    // (Spring 7에서 422의 이름이 UNPROCESSABLE_ENTITY → UNPROCESSABLE_CONTENT로 바뀜. 숫자는 같은 422)
    INSUFFICIENT_BALANCE(HttpStatus.UNPROCESSABLE_CONTENT, "잔액이 부족합니다"),
    ACCOUNT_NOT_ACTIVE(HttpStatus.UNPROCESSABLE_CONTENT, "사용할 수 없는 계좌입니다"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT, "같은 Idempotency-Key로 다른 내용의 이체를 보낼 수 없습니다"),
    SYSTEM_ACCOUNT_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_CONTENT, "이 계좌로는 이체할 수 없습니다"),
    // bank_core가 우리가 모르는 422 code를 줬을 때 (BankCoreClient가 WARN 로그를 남김)
    TRANSFER_NOT_PROCESSABLE(HttpStatus.UNPROCESSABLE_CONTENT, "이체를 처리할 수 없습니다"),

    // 429: 앱의 호출 한도(토큰 버킷)를 다 씀. Retry-After 헤더(초)만큼 기다린 뒤 다시 보내면 된다
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "호출 한도를 초과했습니다. 잠시 후 다시 시도해 주세요"),

    // 500: 예상하지 못한 서버 오류
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다"),

    // 502: 우리 뒤에 있는 bank_core가 꺼져 있음·연결 실패·5xx·연동 계정 로그인 실패 등
    //      (우리 서버는 멀쩡하지만 "뒤쪽 서버"가 제대로 답하지 못했다는 뜻 = Bad Gateway)
    BANK_CORE_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "은행 시스템에 일시적으로 연결할 수 없습니다"),
    // 504: bank_core가 정해 둔 시간(연결 3초 / 응답 5초) 안에 답하지 않음 (= Gateway Timeout)
    BANK_CORE_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "은행 시스템의 응답이 늦어 처리하지 못했습니다");

    private final HttpStatus status;   // 응답에 쓸 HTTP 상태 코드
    private final String message;      // 사람이 읽는 기본 메시지
}
