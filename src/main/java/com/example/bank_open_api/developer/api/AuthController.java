package com.example.bank_open_api.developer.api;

// [무엇] 로그인 API 창구
// [역할] 이메일·비밀번호를 받아 AuthService에 넘기고, 발급된 토큰을 돌려줌 (bank_core의 AuthController와 같은 구조)
// [누가 쓰나] 개발자 포털(프런트엔드), curl. 토큰 없이 호출 가능 (SecurityConfig에서 permitAll)

import com.example.bank_open_api.developer.application.AuthService;
import com.example.bank_open_api.developer.application.AuthService.LoginResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    // 로그인 요청 모양
    // 형식 검사는 "비어 있지 않은지"만 한다. 로그인에서 이메일 형식·비밀번호 길이를 400으로 따로 알려 주면
    // 가입 규칙에 대한 힌트가 될 수 있어서, 나머지는 모두 401 INVALID_CREDENTIALS 하나로 처리
    public record LoginRequest(
            @NotBlank String email,      // 비어 있으면 400
            @NotBlank String password
    ) {
    }

    // 로그인 응답 모양
    public record LoginResponse(
            String accessToken,   // JWT
            String tokenType,     // 항상 "Bearer" (헤더에 "Authorization: Bearer 토큰" 형태로 붙이라는 뜻)
            long expiresIn        // 유효 시간 (초). 3600 = 1시간
    ) {
    }

    /**
     * 로그인
     * POST /api/v1/auth/login
     * - 200: 로그인 성공 (accessToken)
     * - 401: INVALID_CREDENTIALS (이메일이 없거나 비밀번호가 틀림. 둘을 구분하지 않음)
     */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        LoginResult result = authService.login(request.email(), request.password());
        return new LoginResponse(result.accessToken(), "Bearer", result.expiresInSeconds());
    }
}
