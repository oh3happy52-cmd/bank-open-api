package com.example.bank_open_api.common.security;

// [무엇] 문지기(Spring Security) 단계에서 오류 응답 {code, message}를 직접 쓰는 도구
// [역할] Controller에 도착하기 전에 막는 경우에는 GlobalExceptionHandler가 처리하지 못하므로,
//        여기서 같은 모양의 JSON을 직접 써서 응답한다
// [누가 쓰나] ApiKeyAuthenticationFilter(401 INVALID_API_KEY), OpenApiSecurityConfig(401·403)
//            (기존 JWT 규칙표는 SecurityConfig.writeError()를 그대로 쓴다 — 기존 코드는 건드리지 않음)

import com.example.bank_open_api.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public final class SecurityErrorWriter {

    private SecurityErrorWriter() {
    }

    // 메시지는 ErrorCode에 정해 둔 고정 문구뿐이라 문자열을 이어 붙여도 JSON이 깨지지 않는다
    // (요청에서 온 값은 절대 넣지 않는다 → 응답이 항상 똑같고, 키 원문이 섞일 일도 없음)
    public static void write(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.getStatus().value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":\"" + code.name() + "\",\"message\":\"" + code.getMessage() + "\"}");
    }
}
