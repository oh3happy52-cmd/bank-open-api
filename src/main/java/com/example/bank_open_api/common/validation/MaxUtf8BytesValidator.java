package com.example.bank_open_api.common.validation;

// [무엇] @MaxUtf8Bytes 검사기
// [역할] 문자열을 UTF-8 바이트로 바꿔서 길이가 허용치 이하인지 확인한다.
// [누가 쓰나] Bean Validation(@Valid)이 @MaxUtf8Bytes가 붙은 값을 검사할 때 자동으로 사용

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

public class MaxUtf8BytesValidator implements ConstraintValidator<MaxUtf8Bytes, String> {

    private int maxBytes;

    @Override
    public void initialize(MaxUtf8Bytes annotation) {
        this.maxBytes = annotation.value();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // null(값 없음)은 여기서 판단하지 않는다 → @NotBlank 같은 다른 검사가 맡음 (Bean Validation 관례)
        if (value == null) {
            return true;
        }
        return utf8Length(value) <= maxBytes;
    }

    // UTF-8 바이트 수 (다른 곳에서도 같은 기준으로 세도록 공개)
    // 예) "a" = 1바이트, "가" = 3바이트, 이모지 "😀" = 4바이트
    public static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
