package com.example.bank_open_api.common.validation;

// [무엇] "UTF-8로 바꿨을 때 N바이트 이하" 검사용 어노테이션
// [역할] 글자 수가 아니라 바이트 수로 길이를 검사한다.
//        @Size는 글자 수를 세므로, 한글(1글자 = 3바이트)이 섞이면 실제 바이트 길이를 놓친다.
//        예) "가" × 25 = 25글자지만 75바이트 → @Size(max = 72)는 통과, @MaxUtf8Bytes(72)는 실패
// [누가 쓰나] SignupRequest의 password 필드 (BCrypt는 72바이트까지만 쓰므로 그 이상은 400으로 막음)
//            실제 검사 로직은 MaxUtf8BytesValidator

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = MaxUtf8BytesValidator.class)   // 이 어노테이션을 검사할 클래스
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxUtf8Bytes {

    // 허용하는 최대 바이트 수
    int value();

    // 검사 실패 시 메시지. {value} 자리에 위의 숫자가 들어감
    String message() default "UTF-8 기준 {value}바이트 이하여야 합니다";

    // Bean Validation 규칙상 꼭 있어야 하는 항목 (지금은 쓰지 않음)
    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
