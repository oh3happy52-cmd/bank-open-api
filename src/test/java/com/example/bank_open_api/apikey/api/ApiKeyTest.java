package com.example.bank_open_api.apikey.api;

// [무엇] API 키 발급·목록·폐기 테스트 (HTTP 요청부터 DB까지 끝까지)
// [역할] MockMvc로 진짜 HTTP 요청처럼 보내서, 문지기(Spring Security) → Controller → Service → MySQL 전체를 확인
//        - 발급: 원문 모양(boa_live_ + 43글자), Cache-Control: no-store, DB에는 해시만 있고 원문은 없음
//        - 목록: 원문·해시가 응답에 없음
//        - 개수 제한: ACTIVE 3번째 발급 409, 폐기하면 다시 발급 가능, 동시에 5개 요청해도 ACTIVE는 2개
//        - 폐기: 이미 폐기된 키 재폐기 204 (처음 폐기 시각 유지), 없는 키 404
//        - 남의 앱: 발급·목록·폐기 모두 404, 원래 키는 그대로
//        - 토큰 없음: 세 API 모두 401
// [누가 쓰나] ./gradlew test 실행 시 JUnit이 실행 (Docker가 켜져 있어야 함: Testcontainers MySQL)

import com.example.bank_open_api.TestcontainersConfiguration;
import com.example.bank_open_api.apikey.application.ApiKeyHasher;
import com.example.bank_open_api.apikey.application.ApiKeyService;
import com.example.bank_open_api.common.exception.BusinessException;
import com.example.bank_open_api.common.exception.ErrorCode;
import com.example.bank_open_api.common.security.LoginDeveloper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// jwt.secret: .env가 없는 곳(CI 등)에서도 돌아가도록 테스트 전용 키를 넣음 (진짜 키 아님)
@SpringBootTest(properties = "jwt.secret=test-only-jwt-secret-key-0123456789abcdef")
@Import(TestcontainersConfiguration.class)
class ApiKeyTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbcTemplate;   // DB에 실제로 무엇이 저장됐는지 SQL로 직접 확인할 때 사용

    @Autowired
    ApiKeyService apiKeyService; // 동시 발급 테스트에서 여러 스레드가 직접 부름

    MockMvc mockMvc;   // 서버를 켜지 않고 HTTP 요청을 흉내 내는 도구

    // 테스트마다 새로 가입한 개발자 두 명 (A = 나, B = 다른 개발자)과 A의 앱
    String tokenA;
    String tokenB;
    long appIdA;

    @BeforeEach
    void setUp() throws Exception {
        // springSecurity(): 문지기(Spring Security)를 거치도록 설정 → 실제 서버와 같은 순서로 검사됨
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        tokenA = signupAndLogin();
        tokenB = signupAndLogin();
        appIdA = createApp(tokenA, "A의 앱");
    }

    // ───────────── 발급 ─────────────

    @Test
    void 발급_성공하면_201과_원문을_한_번_돌려준다() throws Exception {
        issueKey(tokenA, appIdA)
                .andExpect(status().isCreated())
                // 키 원문이 담긴 응답은 캐시에 남지 않게
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.apiKey", startsWith("boa_live_")))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.keyHash").doesNotExist());

        // 모양 확인: "boa_live_"(9글자) + Base64URL 43글자 = 52글자, keyPrefix는 원문 앞 12글자
        Map<String, Object> issued = issueAndRead(tokenA, appIdA);
        String rawKey = (String) issued.get("apiKey");
        assertThat(rawKey).hasSize(52);
        assertThat(rawKey.substring(9)).matches("[A-Za-z0-9_-]{43}");   // Base64URL 글자만, 패딩(=) 없음
        assertThat(issued.get("keyPrefix")).isEqualTo(rawKey.substring(0, 12));
    }

    @Test
    void DB에는_원문이_없고_SHA256_해시와_앞_12글자만_저장된다() throws Exception {
        Map<String, Object> issued = issueAndRead(tokenA, appIdA);
        String rawKey = (String) issued.get("apiKey");
        long keyId = ((Number) issued.get("id")).longValue();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT app_id, key_prefix, key_hash, status, revoked_at FROM api_keys WHERE id = ?", keyId);

        // 해시: 원문을 SHA-256 한 값과 같고, 소문자 16진수 64글자
        assertThat(row.get("key_hash")).isEqualTo(ApiKeyHasher.sha256Hex(rawKey));
        assertThat((String) row.get("key_hash")).matches("[0-9a-f]{64}");
        // 앞 12글자, 상태
        assertThat(row.get("key_prefix")).isEqualTo(rawKey.substring(0, 12));
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("revoked_at")).isNull();
        assertThat(((Number) row.get("app_id")).longValue()).isEqualTo(appIdA);

        // 원문은 테이블 어디에도 없다 (모든 글자 열을 이어 붙여서 원문이 들어 있는지 검사)
        Integer containsRaw = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM api_keys WHERE CONCAT_WS('|', key_prefix, key_hash, status) LIKE CONCAT('%', ?, '%')",
                Integer.class, rawKey);
        assertThat(containsRaw).isZero();
    }

    // ───────────── 목록 ─────────────

    @Test
    void 목록에는_원문과_해시가_없다() throws Exception {
        Map<String, Object> issued = issueAndRead(tokenA, appIdA);
        String rawKey = (String) issued.get("apiKey");
        String keyHash = ApiKeyHasher.sha256Hex(rawKey);

        String body = listKeys(tokenA, appIdA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(issued.get("id")))
                .andExpect(jsonPath("$[0].keyPrefix").value(rawKey.substring(0, 12)))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[0].apiKey").doesNotExist())
                .andExpect(jsonPath("$[0].keyHash").doesNotExist())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // 칸 이름이 달라도 새어 나가지 않았는지, 응답 글자 전체에서 원문·해시를 찾아본다
        assertThat(body).doesNotContain(rawKey).doesNotContain(keyHash);
    }

    // ───────────── 개수 제한 ─────────────

    @Test
    void ACTIVE_키가_2개면_3번째_발급은_409() throws Exception {
        issueKey(tokenA, appIdA).andExpect(status().isCreated());
        issueKey(tokenA, appIdA).andExpect(status().isCreated());

        issueKey(tokenA, appIdA)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("API_KEY_LIMIT_EXCEEDED"));
    }

    @Test
    void 하나를_폐기하면_다시_발급할_수_있다() throws Exception {
        Map<String, Object> first = issueAndRead(tokenA, appIdA);
        issueAndRead(tokenA, appIdA);
        issueKey(tokenA, appIdA).andExpect(status().isConflict());   // 꽉 참

        revokeKey(tokenA, appIdA, ((Number) first.get("id")).longValue())
                .andExpect(status().isNoContent());

        Map<String, Object> third = issueAndRead(tokenA, appIdA);   // 다시 발급됨
        assertThat(third.get("apiKey")).isNotEqualTo(first.get("apiKey"));   // 새 키는 매번 다름

        // 목록: 3개 (ACTIVE 2 + REVOKED 1), 폐기된 키에는 revokedAt이 있음
        listKeys(tokenA, appIdA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[?(@.status == 'ACTIVE')]", hasSize(2)))
                .andExpect(jsonPath("$[?(@.status == 'REVOKED')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.status == 'REVOKED')].id").value(first.get("id")))
                .andExpect(jsonPath("$[?(@.status == 'REVOKED')].revokedAt").isNotEmpty());
    }

    @Test
    void 같은_앱에_동시에_5개를_발급해도_ACTIVE는_정확히_2개() throws Exception {
        LoginDeveloper developerA = new LoginDeveloper(myDeveloperId(tokenA));
        int requests = 5;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch start = new CountDownLatch(1);   // 모든 스레드가 준비되면 한꺼번에 출발

        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    apiKeyService.issue(developerA, appIdA);
                    return "ISSUED";
                } catch (BusinessException e) {
                    return e.getErrorCode().name();
                }
            }));
        }
        start.countDown();

        List<String> outcomes = new ArrayList<>();
        for (Future<String> result : results) {
            outcomes.add(result.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 2개만 발급되고 나머지 3개는 개수 제한(409)으로 거절
        assertThat(outcomes).filteredOn("ISSUED"::equals).hasSize(2);
        assertThat(outcomes).filteredOn(ErrorCode.API_KEY_LIMIT_EXCEEDED.name()::equals).hasSize(3);

        Integer activeInDb = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM api_keys WHERE app_id = ? AND status = 'ACTIVE'", Integer.class, appIdA);
        assertThat(activeInDb).isEqualTo(2);
    }

    // ───────────── 폐기 ─────────────

    @Test
    void 이미_폐기된_키를_다시_폐기해도_204이고_처음_폐기_시각이_유지된다() throws Exception {
        long keyId = ((Number) issueAndRead(tokenA, appIdA).get("id")).longValue();

        revokeKey(tokenA, appIdA, keyId).andExpect(status().isNoContent());
        String firstRevokedAt = revokedAtOf(tokenA, appIdA, keyId);
        assertThat(firstRevokedAt).isNotNull();

        revokeKey(tokenA, appIdA, keyId).andExpect(status().isNoContent());
        assertThat(revokedAtOf(tokenA, appIdA, keyId)).isEqualTo(firstRevokedAt);
    }

    @Test
    void 내_앱에_없는_키를_폐기하면_404() throws Exception {
        revokeKey(tokenA, appIdA, Long.MAX_VALUE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("API_KEY_NOT_FOUND"));
    }

    // ───────────── 남의 앱 ─────────────

    @Test
    void 남의_앱에는_발급_목록_폐기_모두_404이고_원래_키는_그대로() throws Exception {
        long keyIdOfA = ((Number) issueAndRead(tokenA, appIdA).get("id")).longValue();

        // B가 A의 앱에 발급
        issueKey(tokenB, appIdA)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APP_NOT_FOUND"));
        // B가 A의 앱 키 목록
        listKeys(tokenB, appIdA)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APP_NOT_FOUND"));
        // B가 A의 키 폐기
        revokeKey(tokenB, appIdA, keyIdOfA)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APP_NOT_FOUND"));

        // B가 자기 앱 번호에 A의 키 번호를 붙여 폐기 시도 → 그 앱에 없는 키
        long appIdB = createApp(tokenB, "B의 앱");
        revokeKey(tokenB, appIdB, keyIdOfA)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("API_KEY_NOT_FOUND"));

        // A의 키는 여전히 ACTIVE 1개, B의 시도로 새 키가 생기지도 않음
        listKeys(tokenA, appIdA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    // ───────────── 토큰 없음 ─────────────

    @Test
    void 토큰이_없으면_세_API_모두_401() throws Exception {
        mockMvc.perform(post("/api/v1/apps/" + appIdA + "/keys"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(get("/api/v1/apps/" + appIdA + "/keys"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(delete("/api/v1/apps/" + appIdA + "/keys/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // ───────────── 도우미 ─────────────

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

    // 토큰 주인의 개발자 번호 (/me로 확인)
    private long myDeveloperId(String token) throws Exception {
        String body = mockMvc.perform(get("/api/v1/developers/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    // 앱을 등록하고 앱 번호를 돌려줌
    private long createApp(String token, String name) throws Exception {
        String body = mockMvc.perform(post("/api/v1/apps")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s"}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private ResultActions issueKey(String token, long appId) throws Exception {
        return mockMvc.perform(post("/api/v1/apps/" + appId + "/keys")
                .header("Authorization", "Bearer " + token));
    }

    // 발급하고 응답 JSON을 Map으로 돌려줌 (id, apiKey, keyPrefix, createdAt)
    private Map<String, Object> issueAndRead(String token, long appId) throws Exception {
        String body = issueKey(token, appId)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$");
    }

    private ResultActions listKeys(String token, long appId) throws Exception {
        return mockMvc.perform(get("/api/v1/apps/" + appId + "/keys")
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions revokeKey(String token, long appId, long keyId) throws Exception {
        return mockMvc.perform(delete("/api/v1/apps/" + appId + "/keys/" + keyId)
                .header("Authorization", "Bearer " + token));
    }

    // 목록에서 특정 키의 revokedAt 값을 꺼냄
    private String revokedAtOf(String token, long appId, long keyId) throws Exception {
        String body = listKeys(token, appId)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> values = JsonPath.read(body, "$[?(@.id == " + keyId + ")].revokedAt");
        return values.isEmpty() ? null : values.get(0);
    }
}
