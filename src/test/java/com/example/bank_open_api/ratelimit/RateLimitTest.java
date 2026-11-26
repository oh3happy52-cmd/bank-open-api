package com.example.bank_open_api.ratelimit;

// [무엇] 호출 제한(토큰 버킷) 테스트 — 진짜 Redis(Testcontainers)를 상대로 끝까지 확인
// [역할] - 10번 통과 → 11번째 429 + Retry-After + X-RateLimit-Remaining: 0
//        - 429면 bank_core를 호출하지 않는다
//        - 동시 요청 100개 → 정확히 10개만 통과 (Lua 스크립트가 원자적으로 처리하는지)
//        - 같은 앱의 키 2개는 양동이 1개를 공유 / 다른 앱은 영향 없음
//        - Redis에 ratelimit:app:{appId} Hash(tokens, ts)와 TTL이 저장되는지
// [누가 쓰나] ./gradlew test 실행 시 JUnit이 실행 (Docker 필요: Testcontainers MySQL·Redis)
//
// ── 채우는 속도를 아주 느리게(0.001개/초 = 1000초에 1개) 둔 이유 ──
// 테스트 도중 토큰이 1개라도 다시 채워지면 "정확히 10개"가 11개가 될 수 있다.
// 1000초에 1개면 테스트가 끝날 때까지 채워질 일이 없다. (시간이 지나면 채워지는 것은 RateLimitRefillTest에서 확인)
//
// 테스트마다 새 개발자·새 앱을 만들므로 appId가 매번 달라 → 양동이도 매번 새것 (테스트끼리 서로 영향 없음)

import com.example.bank_open_api.TestcontainersConfiguration;
import com.example.bank_open_api.support.FakeBankCore;
import com.example.bank_open_api.support.OpenApiFixture;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "jwt.secret=test-only-jwt-secret-key-0123456789abcdef",
        "bank-core.username=it-open-api",
        "bank-core.password=it-fake-password-7Qz",
        "rate-limit.capacity=10",
        "rate-limit.refill-per-second=0.001"   // 1000초에 1개 → 테스트 중에는 사실상 채워지지 않음
})
@Import(TestcontainersConfiguration.class)
class RateLimitTest {

    private static final String PING = "/open/v1/ping";
    private static final String API_KEY_HEADER = "X-API-Key";

    // 429일 때 bank_core까지 가지 않는지 세어 보려고 가짜 bank_core를 띄움
    static final FakeBankCore FAKE = FakeBankCore.start("it-open-api", "it-fake-password-7Qz");

    @DynamicPropertySource
    static void bankCoreProperties(DynamicPropertyRegistry registry) {
        registry.add("bank-core.base-url", FAKE::baseUrl);
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    StringRedisTemplate redisTemplate;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        FAKE.reset();
    }

    // ───────────── 1. 10번 통과 → 11번째 429 ─────────────

    @Test
    void 열_번은_통과하고_남은_개수가_하나씩_줄며_열한_번째는_429() throws Exception {
        OpenApiFixture.Issued issued = OpenApiFixture.issueApiKey(mockMvc);

        for (int i = 1; i <= 10; i++) {
            mockMvc.perform(get(PING).header(API_KEY_HEADER, issued.apiKey()))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Limit", "10"))
                    .andExpect(header().string("X-RateLimit-Remaining", String.valueOf(10 - i)))
                    .andExpect(header().doesNotExist("Retry-After"));
        }

        MockHttpServletResponse rejected = mockMvc.perform(get(PING).header(API_KEY_HEADER, issued.apiKey()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(header().string("X-RateLimit-Limit", "10"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andReturn().getResponse();

        // Retry-After: 초 단위 정수, 최소 1. 채우는 속도가 1000초에 1개라 약 1000초
        long retryAfter = Long.parseLong(rejected.getHeader("Retry-After"));
        assertThat(retryAfter).isBetween(1L, 1000L);
        assertThat(retryAfter).isGreaterThan(900L);

        // 응답 모양이 기존 오류 형식({code, message}, JSON)과 같다
        assertThat(rejected.getContentType()).startsWith("application/json");
        String body = rejected.getContentAsString(StandardCharsets.UTF_8);
        Map<String, Object> json = JsonPath.read(body, "$");
        assertThat(json).containsOnlyKeys("code", "message");
    }

    @Test
    void 한도를_넘으면_bank_core를_호출하지_않는다() throws Exception {
        OpenApiFixture.Issued issued = OpenApiFixture.issueApiKey(mockMvc);

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/open/v1/accounts/{n}", "100-0001").header(API_KEY_HEADER, issued.apiKey()))
                    .andExpect(status().isOk());
        }
        assertThat(FAKE.accountCallCount()).isEqualTo(10);

        mockMvc.perform(get("/open/v1/accounts/{n}", "100-0001").header(API_KEY_HEADER, issued.apiKey()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));

        // 11번째는 필터에서 끝났으므로 bank_core 호출 수는 그대로 10
        assertThat(FAKE.accountCallCount()).isEqualTo(10);
    }

    // ───────────── 3. 동시 요청 100개 → 정확히 10개 ─────────────

    @Test
    void 동시에_100개를_보내도_정확히_10개만_통과하고_90개는_429() throws Exception {
        OpenApiFixture.Issued issued = OpenApiFixture.issueApiKey(mockMvc);

        int total = 100;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch ready = new CountDownLatch(total);
        CountDownLatch go = new CountDownLatch(1);   // 출발 신호: 모두 준비된 뒤 한꺼번에 보냄
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < total; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return mockMvc.perform(get(PING).header(API_KEY_HEADER, issued.apiKey()))
                            .andReturn().getResponse().getStatus();
                }));
            }
            go.countDown();

            int ok = 0;
            int tooMany = 0;
            for (Future<Integer> f : futures) {
                int code = f.get();
                if (code == 200) ok++;
                else if (code == 429) tooMany++;
            }
            assertThat(ok).isEqualTo(10);
            assertThat(tooMany).isEqualTo(90);
        } finally {
            pool.shutdownNow();
        }
    }

    // ───────────── 4. 같은 앱은 공유, 다른 앱은 따로 ─────────────

    @Test
    void 같은_앱의_키_두_개는_양동이_하나를_나눠_쓰고_다른_앱은_영향이_없다() throws Exception {
        OpenApiFixture.Issued appA = OpenApiFixture.issueApiKey(mockMvc);
        String appAKey2 = issueSecondKey(appA);
        OpenApiFixture.Issued appB = OpenApiFixture.issueApiKey(mockMvc);

        // 앱 A: 키1로 5번, 키2로 5번 → 합쳐서 10개를 다 씀
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get(PING).header(API_KEY_HEADER, appA.apiKey())).andExpect(status().isOk());
            mockMvc.perform(get(PING).header(API_KEY_HEADER, appAKey2)).andExpect(status().isOk());
        }

        // 앱 A는 어느 키로 보내도 429 (키를 바꿔도 한도를 늘릴 수 없음)
        mockMvc.perform(get(PING).header(API_KEY_HEADER, appA.apiKey())).andExpect(status().isTooManyRequests());
        mockMvc.perform(get(PING).header(API_KEY_HEADER, appAKey2)).andExpect(status().isTooManyRequests());

        // 앱 B는 자기 양동이가 가득 찬 상태 그대로
        mockMvc.perform(get(PING).header(API_KEY_HEADER, appB.apiKey()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Remaining", "9"));
    }

    // ───────────── 저장 모양 ─────────────

    @Test
    void Redis에는_앱별_Hash_tokens_ts와_TTL이_저장된다() throws Exception {
        OpenApiFixture.Issued issued = OpenApiFixture.issueApiKey(mockMvc);
        mockMvc.perform(get(PING).header(API_KEY_HEADER, issued.apiKey())).andExpect(status().isOk());

        String key = "ratelimit:app:" + issued.appId();
        Map<Object, Object> hash = redisTemplate.opsForHash().entries(key);
        assertThat(hash).containsOnlyKeys("tokens", "ts");
        assertThat(Double.parseDouble((String) hash.get("tokens"))).isBetween(8.99, 9.01);
        assertThat(Long.parseLong((String) hash.get("ts"))).isPositive();

        // TTL = 가득 차는 시간(10 / 0.001 = 10000초)의 2배 = 20000초 → 0보다 크고 20000초 이하
        Long ttlSeconds = redisTemplate.getExpire(key);
        assertThat(ttlSeconds).isNotNull().isPositive().isLessThanOrEqualTo(20000L);

        // 키에 API 키 원문이 들어가지 않는다 (양동이 키는 appId만 씀)
        assertThat(redisTemplate.keys("*" + issued.apiKey() + "*")).isEmpty();
    }

    // 같은 앱에 두 번째 키 발급 (앱당 ACTIVE 최대 2개)
    private String issueSecondKey(OpenApiFixture.Issued issued) throws Exception {
        String body = mockMvc.perform(post("/api/v1/apps/" + issued.appId() + "/keys")
                        .header("Authorization", "Bearer " + issued.developerToken()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.apiKey");
    }
}
