package com.example.bank_open_api.usage;

// [무엇] 오픈 API 사용량 기록 테스트 — 진짜 MySQL·Redis(Testcontainers) + 가짜 bank_core
// [역할] 요청을 보내고, 다른 스레드가 DB에 저장할 때까지 기다린 뒤(Awaitility) 기록 내용을 확인
//        - 200·404·502·504 모두 기록되고, 앱·키·메서드·상태 코드·걸린 시간·요청 시각(고정 시계)이 맞다
//        - endpoint에는 주소 틀만: /open/v1/accounts/{accountNumber} (실제 계좌번호는 DB·로그 어디에도 없음)
//        - 429(컨트롤러까지 안 간 요청)도 주소 틀로 기록된다
//        - 맞는 주소·메서드가 없으면 UNMATCHED
//        - 401(키 없음·가짜 키)은 기록되지 않는다
//        - 키 2개를 쓰면 키 번호가 각각 기록된다
// [누가 쓰나] ./gradlew test 실행 시 JUnit이 실행 (Docker 필요)
//
// rate-limit.capacity=5, refill 0.001/초: 한 테스트에서 5번까지는 통과, 6번째부터 429 (테스트 중 다시 채워지지 않음)

import com.example.bank_open_api.TestcontainersConfiguration;
import com.example.bank_open_api.apikey.application.ApiKeyHasher;
import com.example.bank_open_api.apikey.domain.ApiKeyRepository;
import com.example.bank_open_api.support.FakeBankCore;
import com.example.bank_open_api.support.MutableClock;
import com.example.bank_open_api.support.OpenApiFixture;
import com.example.bank_open_api.support.TestClockConfiguration;
import com.example.bank_open_api.usage.domain.ApiUsageLog;
import com.example.bank_open_api.usage.domain.ApiUsageLogRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "jwt.secret=test-only-jwt-secret-key-0123456789abcdef",
        "bank-core.username=it-open-api",
        "bank-core.password=it-fake-password-7Qz",
        "bank-core.read-timeout=1s",           // 504 테스트가 오래 걸리지 않게
        "rate-limit.capacity=5",
        "rate-limit.refill-per-second=0.001"
})
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class UsageLogTest {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String ACCOUNTS_TEMPLATE = "/open/v1/accounts/{accountNumber}";
    // DB·로그에 절대 나오면 안 되는 실제 계좌번호 (다른 곳에서 우연히 나올 수 없게 특이한 값)
    private static final String ACCOUNT_NUMBER = "987-6543210";

    static final FakeBankCore FAKE = FakeBankCore.start("it-open-api", "it-fake-password-7Qz");

    @DynamicPropertySource
    static void bankCoreProperties(DynamicPropertyRegistry registry) {
        registry.add("bank-core.base-url", FAKE::baseUrl);
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    ApiUsageLogRepository usageLogRepository;

    @Autowired
    ApiKeyRepository apiKeyRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MutableClock clock;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        FAKE.reset();
        clock.set(TestClockConfiguration.INITIAL_TIME);
    }

    // ───────────── 기본 내용 ─────────────

    @Test
    void 성공한_요청은_앱_키_메서드_주소틀_상태_시간_시각이_기록된다() throws Exception {
        OpenApiFixture.Issued issued = OpenApiFixture.issueApiKey(mockMvc);
        LocalDateTime requestTime = LocalDateTime.of(2026, 11, 26, 9, 30, 15, 123_000_000);
        clock.set(requestTime);

        mockMvc.perform(get("/open/v1/ping").header(API_KEY_HEADER, issued.apiKey()))
                .andExpect(status().isOk());

        ApiUsageLog log = awaitLogs(issued.appId(), 1).getFirst();
        assertThat(log.getAppId()).isEqualTo(issued.appId());
        assertThat(log.getApiKeyId()).isEqualTo(keyIdOf(issued.apiKey()));
        assertThat(log.getMethod()).isEqualTo("GET");
        assertThat(log.getEndpoint()).isEqualTo("/open/v1/ping");
        assertThat(log.getStatusCode()).isEqualTo((short) 200);
        assertThat(log.getLatencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(log.getRequestedAt()).isEqualTo(requestTime);   // 고정 시계 그대로 (밀리초까지)
    }

    // ───────────── 결과와 상관없이 기록 + 주소 틀 ─────────────

    @Test
    void 결과가_200_404_502_504여도_모두_주소틀로_기록되고_계좌번호는_어디에도_없다(CapturedOutput output)
            throws Exception {
        OpenApiFixture.Issued issued = OpenApiFixture.issueApiKey(mockMvc);
        String path = "/open/v1/accounts/{n}";

        mockMvc.perform(get(path, ACCOUNT_NUMBER).header(API_KEY_HEADER, issued.apiKey()))
                .andExpect(status().isOk());
        FAKE.respondWithStatus(404);
        mockMvc.perform(get(path, ACCOUNT_NUMBER).header(API_KEY_HEADER, issued.apiKey()))
                .andExpect(status().isNotFound());
        FAKE.respondWithStatus(500);
        mockMvc.perform(get(path, ACCOUNT_NUMBER).header(API_KEY_HEADER, issued.apiKey()))
                .andExpect(status().isBadGateway());
        FAKE.respondSlowly(1_500);   // 응답 제한 시간(1초)보다 오래 → 504
        mockMvc.perform(get(path, ACCOUNT_NUMBER).header(API_KEY_HEADER, issued.apiKey()))
                .andExpect(status().isGatewayTimeout());

        List<ApiUsageLog> logs = awaitLogs(issued.appId(), 4);
        assertThat(logs).extracting(ApiUsageLog::getStatusCode)
                .containsExactlyInAnyOrder((short) 200, (short) 404, (short) 502, (short) 504);
        assertThat(logs).extracting(ApiUsageLog::getEndpoint).containsOnly(ACCOUNTS_TEMPLATE);

        // 504는 1초 넘게 기다렸으므로 걸린 시간도 그만큼 기록된다
        assertThat(logs).filteredOn(l -> l.getStatusCode() == 504)
                .extracting(ApiUsageLog::getLatencyMs)
                .allSatisfy(ms -> assertThat(ms).isGreaterThanOrEqualTo(1_000));

        // DB 전체 어디에도 실제 계좌번호가 없다 (endpoint 열을 문자열로 직접 검사)
        Integer leaked = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM api_usage_logs WHERE endpoint LIKE ?", Integer.class,
                "%" + ACCOUNT_NUMBER + "%");
        assertThat(leaked).isZero();
        // 로그에도 계좌번호 전체가 없다 (마스킹된 끝 4자리만 허용)
        assertThat(output.getAll()).doesNotContain(ACCOUNT_NUMBER);
    }

    @Test
    void 호출_한도를_넘은_429도_컨트롤러를_거치지_않았지만_주소틀로_기록된다() throws Exception {
        OpenApiFixture.Issued issued = OpenApiFixture.issueApiKey(mockMvc);

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/open/v1/accounts/{n}", ACCOUNT_NUMBER).header(API_KEY_HEADER, issued.apiKey()))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/open/v1/accounts/{n}", ACCOUNT_NUMBER).header(API_KEY_HEADER, issued.apiKey()))
                .andExpect(status().isTooManyRequests());

        // 6번째는 bank_core까지 가지 않았다
        assertThat(FAKE.accountCallCount()).isEqualTo(5);

        List<ApiUsageLog> logs = awaitLogs(issued.appId(), 6);
        assertThat(logs).filteredOn(l -> l.getStatusCode() == 429)
                .singleElement()
                .extracting(ApiUsageLog::getEndpoint)
                .isEqualTo(ACCOUNTS_TEMPLATE);
    }

    @Test
    void 맞는_주소나_메서드가_없으면_UNMATCHED로_기록된다() throws Exception {
        OpenApiFixture.Issued issued = OpenApiFixture.issueApiKey(mockMvc);

        // 없는 주소 (뒤에 계좌번호 같은 값이 붙어도 저장되지 않음)
        mockMvc.perform(get("/open/v1/accounts/{n}/extra", ACCOUNT_NUMBER).header(API_KEY_HEADER, issued.apiKey()))
                .andExpect(status().isNotFound());
        // 주소는 있지만 메서드가 없음 (ping은 GET만)
        mockMvc.perform(post("/open/v1/ping").header(API_KEY_HEADER, issued.apiKey()))
                .andExpect(status().isMethodNotAllowed());

        List<ApiUsageLog> logs = awaitLogs(issued.appId(), 2);
        assertThat(logs).extracting(ApiUsageLog::getEndpoint).containsOnly("UNMATCHED");
        assertThat(logs).extracting(ApiUsageLog::getStatusCode)
                .containsExactlyInAnyOrder((short) 404, (short) 405);
    }

    // ───────────── 기록하지 않는 것 ─────────────

    @Test
    void 키가_없거나_가짜면_401이고_기록하지_않는다() throws Exception {
        OpenApiFixture.Issued issued = OpenApiFixture.issueApiKey(mockMvc);
        long before = usageLogRepository.count();

        mockMvc.perform(get("/open/v1/ping")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/open/v1/ping").header(API_KEY_HEADER, "boa_live_fake"))
                .andExpect(status().isUnauthorized());
        // 마지막에 정상 요청 하나 → 이것이 저장될 때까지 기다리면, 앞의 401이 기록됐다면 같이 저장됐을 것
        mockMvc.perform(get("/open/v1/ping").header(API_KEY_HEADER, issued.apiKey()))
                .andExpect(status().isOk());

        awaitLogs(issued.appId(), 1);
        assertThat(usageLogRepository.count()).isEqualTo(before + 1);
    }

    // ───────────── 키별 기록 ─────────────

    @Test
    void 같은_앱의_키_두_개는_각자의_키_번호로_기록된다() throws Exception {
        OpenApiFixture.Issued issued = OpenApiFixture.issueApiKey(mockMvc);
        String secondKey = issueSecondKey(issued);

        mockMvc.perform(get("/open/v1/ping").header(API_KEY_HEADER, issued.apiKey())).andExpect(status().isOk());
        mockMvc.perform(get("/open/v1/ping").header(API_KEY_HEADER, secondKey)).andExpect(status().isOk());

        List<ApiUsageLog> logs = awaitLogs(issued.appId(), 2);
        assertThat(logs).extracting(ApiUsageLog::getApiKeyId)
                .containsExactlyInAnyOrder(keyIdOf(issued.apiKey()), keyIdOf(secondKey));
    }

    // ───────────── 도우미 ─────────────

    // 비동기 저장이라 바로 DB에 없을 수 있다 → 최대 5초 동안 기다리며 확인
    private List<ApiUsageLog> awaitLogs(long appId, int expectedCount) {
        await().atMost(Duration.ofSeconds(5))
                .until(() -> usageLogRepository.findAllByAppIdOrderByIdDesc(appId).size() >= expectedCount);
        List<ApiUsageLog> logs = usageLogRepository.findAllByAppIdOrderByIdDesc(appId);
        assertThat(logs).hasSize(expectedCount);
        return logs;
    }

    private long keyIdOf(String rawKey) {
        return apiKeyRepository.findByKeyHash(ApiKeyHasher.sha256Hex(rawKey)).orElseThrow().getId();
    }

    private String issueSecondKey(OpenApiFixture.Issued issued) throws Exception {
        String body = mockMvc.perform(post("/api/v1/apps/" + issued.appId() + "/keys")
                        .header("Authorization", "Bearer " + issued.developerToken()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.apiKey");
    }
}
