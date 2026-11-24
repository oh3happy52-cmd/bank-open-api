package com.example.bank_open_api.support;

// [무엇] 오픈 API 테스트 준비 도우미
// [역할] "개발자 가입 → 로그인 → 앱 등록 → API 키 발급"을 한 번에 해서, 앱 번호와 키 원문을 돌려준다
//        (계좌 조회 테스트들이 매번 같은 준비를 하므로 한곳에 모음)
// [누가 쓰나] BankCoreAccountTest, BankCoreDownTest

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public final class OpenApiFixture {

    private OpenApiFixture() {
    }

    // 준비 결과: 개발자 토큰(JWT), 앱 번호, API 키 원문
    public record Issued(String developerToken, long appId, String apiKey) {
    }

    // 새 개발자(매번 다른 이메일)로 앱을 만들고 API 키를 하나 발급
    public static Issued issueApiKey(MockMvc mockMvc) throws Exception {
        String email = "dev-" + UUID.randomUUID() + "@example.com";
        String password = "password1234";

        mockMvc.perform(post("/api/v1/developers/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s", "name": "개발자"}
                                """.formatted(email, password)))
                .andExpect(status().isCreated());

        String loginBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String token = JsonPath.read(loginBody, "$.accessToken");

        String appBody = mockMvc.perform(post("/api/v1/apps")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "핀테크 앱"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long appId = ((Number) JsonPath.read(appBody, "$.id")).longValue();

        String keyBody = mockMvc.perform(post("/api/v1/apps/" + appId + "/keys")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String apiKey = JsonPath.read(keyBody, "$.apiKey");

        return new Issued(token, appId, apiKey);
    }
}
