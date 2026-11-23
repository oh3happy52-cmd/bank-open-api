package com.example.bank_open_api.developer.api;

// [무엇] 회원가입 요청 모양 (JSON 본문)
// [역할] 들어온 값이 규칙에 맞는지 @Valid로 검사. 하나라도 어기면 400 INVALID_REQUEST
// [누가 쓰나] DeveloperController (POST /api/v1/developers/signup)

import com.example.bank_open_api.common.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank(message = "이메일을 입력하세요")
        @Email(message = "이메일 형식이 올바르지 않습니다")
        @Size(max = 255, message = "이메일은 255자 이하여야 합니다")
        String email,

        // 최소: 글자 수 8자 이상
        // 최대: UTF-8 기준 72바이트 이하 (BCrypt가 72바이트까지만 쓰기 때문. 한글은 1글자 = 3바이트)
        @NotBlank(message = "비밀번호를 입력하세요")
        @Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다")
        @MaxUtf8Bytes(72)
        String password,

        @NotBlank(message = "이름을 입력하세요")
        @Size(max = 50, message = "이름은 50자 이하여야 합니다")
        String name
) {
}
