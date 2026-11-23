package com.example.bank_open_api.common.exception;

// [무엇] 공통 오류 응답 테스트 (없는 주소, 지원하지 않는 메서드)
// [역할] 예전에는 "예상 못 한 오류" 처리기에 잡혀 500이 나던 경우들이
//        올바른 상태 코드(404/405)와 {code, message} 모양으로 나가는지 확인
//        토큰이 없으면 문지기가 먼저 막아 401이 되는 것도 확인 (주소 존재 여부를 로그인 전에는 알려 주지 않음)
// [누가 쓰나] ./gradlew test 실행 시 JUnit이 실행 (Docker가 켜져 있어야 함: Testcontainers MySQL)

import com.example.bank_open_api.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// jwt.secret: .env가 없는 곳(CI 등)에서도 돌아가도록 테스트 전용 키를 넣음 (진짜 키 아님)
@SpringBootTest(properties = "jwt.secret=test-only-jwt-secret-key-0123456789abcdef")
@Import(TestcontainersConfiguration.class)
class ErrorHandlingTest {

    @Autowired
    WebApplicationContext context;

    MockMvc mockMvc;
    String token;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        token = signupAndLogin();
    }

    @Test
    void 로그인한_상태로_없는_주소를_부르면_500이_아니라_404() throws Exception {
        mockMvc.perform(get("/api/v1/nothing").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 지원하지_않는_메서드로_부르면_500이_아니라_405() throws Exception {
        // /me는 GET만 받는 주소
        mockMvc.perform(delete("/api/v1/developers/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void 토큰_없이_없는_주소를_부르면_404가_아니라_401() throws Exception {
        mockMvc.perform(get("/api/v1/nothing"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // 새 개발자를 가입시키고 로그인해서 토큰을 돌려줌
    private String signupAndLogin() throws Exception {
        String email = "dev-" + UUID.randomUUID() + "@example.com";
        String password = "password1234";

        mockMvc.perform(post("/api/v1/developers/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s", "name": "개발자"}
                                """.formatted(email, password)))
                .andExpect(status().isCreated());

        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.accessToken");
    }
}
