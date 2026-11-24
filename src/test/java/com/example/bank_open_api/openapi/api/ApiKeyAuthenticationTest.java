package com.example.bank_open_api.openapi.api;

// [무엇] API 키 인증(/open/v1/**) 테스트 (HTTP 요청부터 DB까지 끝까지)
// [역할] MockMvc로 진짜 HTTP 요청처럼 보내서 문지기 규칙표 두 개(API 키 / JWT)가 제대로 나뉘는지 확인
//        - 올바른 키 200 {appId, keyPrefix}
//        - 헤더 없음 / 가짜 키 / 폐기한 키 → 401 INVALID_API_KEY, 세 응답 본문이 글자 하나까지 같음
//        - 폐기 API 직후 바로 다음 요청부터 막힘
//        - /open/v1 에 JWT만 보내면 401, /api/v1/developers/me 에 API 키만 보내면 401
//        - 로그에 실패 원인(reason=...)은 남고, 키 원문·해시는 없음
// [누가 쓰나] ./gradlew test 실행 시 JUnit이 실행 (Docker가 켜져 있어야 함: Testcontainers MySQL)

import com.example.bank_open_api.TestcontainersConfiguration;
import com.example.bank_open_api.apikey.application.ApiKeyHasher;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// jwt.secret: 테스트 전용 키 (진짜 키 아님)
// logging.level ...=DEBUG: 인증 "성공" 로그(debug)까지 찍히게 해서, 성공 로그에도 원문이 없는지 함께 검사
@SpringBootTest(properties = {
        "jwt.secret=test-only-jwt-secret-key-0123456789abcdef",
        "logging.level.com.example.bank_open_api.common.security=DEBUG"
})
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)   // 테스트 중 화면(로그)에 찍힌 글자를 모아서 검사할 수 있게 함
class ApiKeyAuthenticationTest {

    private static final String PING = "/open/v1/ping";
    private static final String API_KEY_HEADER = "X-API-Key";

    @Autowired
    WebApplicationContext context;

    MockMvc mockMvc;

    // 테스트마다 새로 가입한 개발자 한 명, 그 개발자의 앱, 그 앱에 발급한 키 하나
    String token;
    long appId;
    long keyId;
    String rawKey;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())   // 문지기(Spring Security)를 거치게 함
                .build();
        token = signupAndLogin();
        appId = createApp(token);
        Map<String, Object> issued = issueKey(token, appId);
        keyId = ((Number) issued.get("id")).longValue();
        rawKey = (String) issued.get("apiKey");
    }

    // ───────────── 성공 ─────────────

    @Test
    void 올바른_키면_200과_appId_keyPrefix를_돌려준다() throws Exception {
        String body = mockMvc.perform(get(PING).header(API_KEY_HEADER, rawKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appId").value(appId))
                .andExpect(jsonPath("$.keyPrefix").value(rawKey.substring(0, 12)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // 응답 어디에도 원문·해시가 없다
        assertThat(body).doesNotContain(rawKey).doesNotContain(ApiKeyHasher.sha256Hex(rawKey));
    }

    // ───────────── 실패 3가지 ─────────────

    @Test
    void 헤더가_없으면_401() throws Exception {
        expectInvalidApiKey(mockMvc.perform(get(PING)));
    }

    @Test
    void 가짜_키면_401() throws Exception {
        // 모양은 진짜와 같지만 발급한 적 없는 키
        String fakeKey = "boa_live_" + "A".repeat(43);
        expectInvalidApiKey(mockMvc.perform(get(PING).header(API_KEY_HEADER, fakeKey)));
    }

    @Test
    void 폐기한_키는_폐기_직후_바로_401() throws Exception {
        // 폐기 전에는 통과
        mockMvc.perform(get(PING).header(API_KEY_HEADER, rawKey)).andExpect(status().isOk());

        // 개발자 포털에서 폐기 (JWT)
        mockMvc.perform(delete("/api/v1/apps/" + appId + "/keys/" + keyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // 바로 다음 요청부터 막힘 (기다리지 않음)
        expectInvalidApiKey(mockMvc.perform(get(PING).header(API_KEY_HEADER, rawKey)));
    }

    @Test
    void 헤더없음_가짜키_폐기키의_401_응답이_글자_하나까지_똑같다() throws Exception {
        MockHttpServletResponse missing = mockMvc.perform(get(PING))
                .andReturn().getResponse();
        MockHttpServletResponse notFound = mockMvc.perform(get(PING).header(API_KEY_HEADER, "boa_live_fake"))
                .andReturn().getResponse();

        mockMvc.perform(delete("/api/v1/apps/" + appId + "/keys/" + keyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        MockHttpServletResponse revoked = mockMvc.perform(get(PING).header(API_KEY_HEADER, rawKey))
                .andReturn().getResponse();

        String expected = missing.getContentAsString(StandardCharsets.UTF_8);
        assertThat(expected).contains("\"code\":\"INVALID_API_KEY\"");

        for (MockHttpServletResponse response : new MockHttpServletResponse[]{missing, notFound, revoked}) {
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentType()).isEqualTo(missing.getContentType());
            assertThat(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(expected);
        }
    }

    // ───────────── 두 인증 방식이 섞이지 않음 ─────────────

    @Test
    void open_v1에_JWT만_보내면_401() throws Exception {
        // 개발자 포털 토큰은 오픈 API에서 통하지 않는다
        expectInvalidApiKey(mockMvc.perform(get(PING).header("Authorization", "Bearer " + token)));
    }

    @Test
    void developers_me에_API_키만_보내면_401() throws Exception {
        // 반대로 API 키는 개발자 포털 API에서 통하지 않는다 (JWT 규칙표의 401)
        mockMvc.perform(get("/api/v1/developers/me").header(API_KEY_HEADER, rawKey))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // ───────────── 로그 ─────────────

    @Test
    void 로그에는_원인만_남고_키_원문과_해시는_없다(CapturedOutput output) throws Exception {
        String keyHash = ApiKeyHasher.sha256Hex(rawKey);
        String fakeKey = "boa_live_" + "Z".repeat(43);

        mockMvc.perform(get(PING).header(API_KEY_HEADER, rawKey)).andExpect(status().isOk());       // 성공(debug)
        mockMvc.perform(get(PING)).andExpect(status().isUnauthorized());                            // missing
        mockMvc.perform(get(PING).header(API_KEY_HEADER, fakeKey)).andExpect(status().isUnauthorized()); // not_found
        mockMvc.perform(delete("/api/v1/apps/" + appId + "/keys/" + keyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(PING).header(API_KEY_HEADER, rawKey)).andExpect(status().isUnauthorized()); // revoked

        String logs = output.getAll();

        // 원인은 구분해서 남는다
        assertThat(logs).contains("API 키 인증 성공");
        assertThat(logs).contains("reason=missing");
        assertThat(logs).contains("reason=not_found");
        assertThat(logs).contains("reason=revoked");
        // 찾은 경우에는 keyPrefix가 남는다
        assertThat(logs).contains("keyPrefix=" + rawKey.substring(0, 12));

        // 원문(진짜 키·가짜 키)과 해시는 어디에도 없다
        assertThat(logs).doesNotContain(rawKey);
        assertThat(logs).doesNotContain(fakeKey);
        assertThat(logs).doesNotContain(keyHash);
        assertThat(logs).doesNotContain(ApiKeyHasher.sha256Hex(fakeKey));
    }

    // ───────────── 도우미 ─────────────

    // 401 INVALID_API_KEY 인지 확인
    private void expectInvalidApiKey(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_API_KEY"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // 새 개발자를 가입시키고 로그인해서 토큰을 돌려줌 (테스트끼리 섞이지 않게 매번 다른 이메일)
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

    // 앱을 등록하고 앱 번호를 돌려줌
    private long createApp(String token) throws Exception {
        String body = mockMvc.perform(post("/api/v1/apps")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "핀테크 앱"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    // 키를 발급하고 응답 JSON을 Map으로 돌려줌 (id, apiKey, keyPrefix, createdAt)
    private Map<String, Object> issueKey(String token, long appId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/apps/" + appId + "/keys")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$");
    }
}
