package com.example.bank_open_api.common.exception;

// [무엇] 전역 예외 처리기
// [역할] 모든 Controller에서 터진 예외를 한곳에서 받아 같은 모양({code, message})의 응답으로 바꿈
//        (bank_core의 GlobalExceptionHandler와 같은 구조)
// [누가 쓰나] Spring이 자동으로 사용 (@RestControllerAdvice). 직접 부르는 곳은 없음
//
// 참고: 토큰이 없거나 잘못된 401은 Controller에 도착하기 전에 문지기(Spring Security)가 막기 때문에
//       여기가 아니라 SecurityConfig.writeError()에서 같은 모양으로 응답한다.

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // DB UNIQUE 제약 이름. 같은 값이 두 번 저장되려 할 때 에러 메시지에 들어 있음
    private static final String DEVELOPER_EMAIL_CONSTRAINT = "uk_developers_email";    // V1: 같은 이메일
    private static final String APP_NAME_CONSTRAINT = "uk_apps_developer_name";        // V2: 같은 개발자의 같은 앱 이름

    // 업무 규칙 위반 (이메일 중복, 로그인 실패 등)
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        log.info("업무 오류: code={}, message={}", code.name(), e.getMessage());
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code, e.getMessage()));
    }

    // DB 제약 조건 위반
    // - 같은 값(이메일, 같은 개발자의 앱 이름)으로 요청이 "동시에" 두 개 들어와, 서비스의 중복 확인을
    //   둘 다 통과한 뒤 늦게 저장하는 쪽이 UNIQUE 규칙에 걸린 경우 → 409
    //   (순서대로 들어오면 DeveloperService·AppService가 먼저 막음)
    // - 그 밖의 제약 위반은 우리 코드의 버그일 가능성이 높으므로 → 500 + 에러 로그
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException e) {
        String cause = e.getMostSpecificCause().getMessage();
        if (cause != null && cause.contains(DEVELOPER_EMAIL_CONSTRAINT)) {
            ErrorCode code = ErrorCode.DUPLICATE_EMAIL;
            log.info("동시 가입 요청 중 이메일 중복 차단");
            return ResponseEntity.status(code.getStatus())
                    .body(ErrorResponse.of(code, code.getMessage()));
        }
        if (cause != null && cause.contains(APP_NAME_CONSTRAINT)) {
            ErrorCode code = ErrorCode.DUPLICATE_APP_NAME;
            log.info("동시 등록 요청 중 앱 이름 중복 차단");
            return ResponseEntity.status(code.getStatus())
                    .body(ErrorResponse.of(code, code.getMessage()));
        }
        log.error("DB 제약 조건 위반", e);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code, code.getMessage()));
    }

    // 요청 값 검사(@Valid) 실패: 예) 이메일 형식이 아님, 비밀번호 8자 미만
    // 첫 번째 오류 하나만 "필드: 이유" 형태로 메시지에 덧붙임
    // (rejectedValue(입력값)는 메시지에 넣지 않음 → 비밀번호가 응답·로그에 나가지 않게)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse("");
        ErrorCode code = ErrorCode.INVALID_REQUEST;
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code, code.getMessage() + " (" + detail + ")"));
    }

    // 요청 본문(JSON)을 읽을 수 없음: 예) 형식이 깨졌거나 본문이 없음
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException e) {
        ErrorCode code = ErrorCode.INVALID_REQUEST;
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code, code.getMessage() + " (요청 본문 형식 오류)"));
    }

    // 주소의 값 형식이 틀림: 예) /api/v1/apps/abc (appId 자리에 숫자가 아닌 글자)
    // 이 처리가 없으면 아래 "예상 못 한 오류"로 빠져 500이 된다
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        ErrorCode code = ErrorCode.INVALID_REQUEST;
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code, code.getMessage() + " (" + e.getName() + " 형식 오류)"));
    }

    // 없는 주소: 예) /api/v1/nothing
    // Spring은 이 경우 NoResourceFoundException을 던지는데, 처리가 없으면 아래 "예상 못 한 오류"에 잡혀 500이 된다
    // (토큰이 없으면 문지기가 먼저 401로 막으므로, 이 404는 로그인한 사용자에게만 보인다)
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e) {
        ErrorCode code = ErrorCode.NOT_FOUND;
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code, code.getMessage()));
    }

    // 주소는 있지만 지원하지 않는 메서드: 예) DELETE /api/v1/developers/me (GET만 받는 주소)
    // 마찬가지로 처리가 없으면 500이 된다
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        ErrorCode code = ErrorCode.METHOD_NOT_ALLOWED;
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code, code.getMessage()));
    }

    // 그 밖의 예상 못 한 오류: 사용자에게는 자세한 내용을 숨기고, 서버 로그에만 전체 내용을 남김
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("예상하지 못한 오류", e);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code, code.getMessage()));
    }
}
