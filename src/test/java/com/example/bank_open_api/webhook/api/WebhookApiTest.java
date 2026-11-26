package com.example.bank_open_api.webhook.api;

// [무엇] 웹훅 설정 API 테스트 (개발자 포털, JWT) — 진짜 MySQL
// [역할] - PUT 처음 등록: 201 + 비밀키 원문 1회 + Cache-Control no-store, DB에는 평문 없음(암호문을 풀면 같은 값)
//        - GET·두 번째 PUT: 비밀키 원문 없음, 앞부분만
//        - DELETE: 꺼짐 → PUT으로 다시 켜짐(비밀키 그대로)
//        - rotate: 새 비밀키 1회 + no-store, 앞부분이 바뀜
//        - URL 검사 400, 남의 앱·없는 앱 404, 웹훅 미등록 404
//        - 전송 기록: 최신순 페이지, 기본 20, 최대 100, 잘못된 page·size 400, payload는 내보내지 않음
// [누가 쓰나] ./gradlew test (Docker 필요)

import com.example.bank_open_api.TestcontainersConfiguration;
import com.example.bank_open_api.support.OpenApiFixture;
import com.example.bank_open_api.support.TestClockConfiguration;
import com.example.bank_open_api.webhook.crypto.WebhookSecretCipher;
import com.example.bank_open_api.webhook.domain.WebhookDelivery;
import com.example.bank_open_api.webhook.domain.WebhookDeliveryRepository;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "jwt.secret=test-only-jwt-secret-key-0123456789abcdef")
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
class WebhookApiTest {

    private static final String URL = "https://fintech.example.com/webhooks";

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    WebhookSecretCipher cipher;

    @Autowired
    WebhookDeliveryRepository deliveryRepository;

    MockMvc mockMvc;
    OpenApiFixture.Issued issued;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        issued = OpenApiFixture.issueApiKey(mockMvc);
    }

    // ───────────── 1. 비밀키 ─────────────

    @Test
    void 처음_등록하면_201과_비밀키_원문을_한_번만_주고_DB에는_암호문만_있다() throws Exception {
        String body = upsert(issued.appId(), URL)
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.url").value(URL))
                .andExpect(jsonPath("$.enabled").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String secret = JsonPath.read(body, "$.secret");
        String prefix = JsonPath.read(body, "$.secretPrefix");
        assertThat(secret).matches("whsec_[A-Za-z0-9_-]{43}");
        assertThat(prefix).isEqualTo(secret.substring(0, 12));

        // DB: 평문이 어디에도 없고, 암호문을 풀면 같은 값
        String stored = jdbcTemplate.queryForObject(
                "SELECT secret_ciphertext FROM webhook_endpoints WHERE app_id = ?", String.class, issued.appId());
        assertThat(stored).doesNotContain(secret).doesNotContain(secret.substring(6));
        assertThat(cipher.decrypt(stored, issued.appId())).isEqualTo(secret);
        Integer plainAnywhere = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM webhook_endpoints WHERE CONCAT(url, secret_ciphertext, secret_prefix) LIKE ?",
                Integer.class, "%" + secret + "%");
        assertThat(plainAnywhere).isZero();

        // 그 뒤로는 비밀키 원문이 다시 나오지 않는다
        getWebhook(issued.appId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secretPrefix").value(prefix))
                .andExpect(jsonPath("$.secret").doesNotExist());
        upsert(issued.appId(), URL + "/v2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(URL + "/v2"))
                .andExpect(jsonPath("$.secretPrefix").value(prefix))   // 주소만 바뀌고 비밀키는 그대로
                .andExpect(jsonPath("$.secret").doesNotExist());
    }

    @Test
    void 끄면_enabled_false이고_PUT으로_다시_켜도_비밀키는_그대로() throws Exception {
        String prefix = JsonPath.read(upsert(issued.appId(), URL).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8), "$.secretPrefix");

        mockMvc.perform(delete(path(issued.appId())).header("Authorization", bearer()))
                .andExpect(status().isNoContent());
        getWebhook(issued.appId()).andExpect(jsonPath("$.enabled").value(false));
        mockMvc.perform(delete(path(issued.appId())).header("Authorization", bearer()))
                .andExpect(status().isNoContent());   // 이미 꺼져 있어도 204

        upsert(issued.appId(), URL)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.secretPrefix").value(prefix));
    }

    @Test
    void rotate는_새_비밀키를_한_번만_주고_DB의_키가_바뀐다() throws Exception {
        String first = JsonPath.read(upsert(issued.appId(), URL).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8), "$.secret");

        String body = mockMvc.perform(post(path(issued.appId()) + "/secret/rotate").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String rotated = JsonPath.read(body, "$.secret");

        assertThat(rotated).matches("whsec_[A-Za-z0-9_-]{43}").isNotEqualTo(first);
        assertThat(JsonPath.<String>read(body, "$.secretPrefix")).isEqualTo(rotated.substring(0, 12));
        String stored = jdbcTemplate.queryForObject(
                "SELECT secret_ciphertext FROM webhook_endpoints WHERE app_id = ?", String.class, issued.appId());
        assertThat(cipher.decrypt(stored, issued.appId())).isEqualTo(rotated);
        getWebhook(issued.appId()).andExpect(jsonPath("$.secretPrefix").value(rotated.substring(0, 12)));
    }

    // ───────────── 2. URL 검사·권한 ─────────────

    @Test
    void 규칙에_맞지_않는_주소는_400_INVALID_WEBHOOK_URL이고_저장되지_않는다() throws Exception {
        for (String bad : List.of("ftp://fintech.example.com/w", "https://user:pass@fintech.example.com/w",
                "not a url", "https://fintech.example.com/" + "a".repeat(2048))) {
            upsert(issued.appId(), bad)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_WEBHOOK_URL"));
        }
        upsert(issued.appId(), "").andExpect(status().isBadRequest());
        getWebhook(issued.appId()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("WEBHOOK_NOT_FOUND"));
    }

    @Test
    void 남의_앱과_없는_앱은_모든_웹훅_API가_404이고_남의_설정은_그대로() throws Exception {
        OpenApiFixture.Issued other = OpenApiFixture.issueApiKey(mockMvc);
        mockMvc.perform(put(path(other.appId())).header("Authorization", "Bearer " + other.developerToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + URL + "\"}"))
                .andExpect(status().isCreated());

        for (long appId : new long[]{other.appId(), 999_999_999L}) {
            upsert(appId, URL + "/hijack").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("APP_NOT_FOUND"));
            getWebhook(appId).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("APP_NOT_FOUND"));
            mockMvc.perform(delete(path(appId)).header("Authorization", bearer())).andExpect(status().isNotFound());
            mockMvc.perform(post(path(appId) + "/secret/rotate").header("Authorization", bearer()))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get(path(appId) + "/deliveries").header("Authorization", bearer()))
                    .andExpect(status().isNotFound());
        }

        mockMvc.perform(get(path(other.appId())).header("Authorization", "Bearer " + other.developerToken()))
                .andExpect(jsonPath("$.url").value(URL))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(get(path(issued.appId()))).andExpect(status().isUnauthorized());
    }

    // ───────────── 전송 기록 목록 ─────────────

    @Test
    void 전송_기록은_최신순_페이지이고_기본_20개_최대_100개이며_payload는_없다() throws Exception {
        List<WebhookDelivery> rows = new ArrayList<>();
        LocalDateTime base = LocalDateTime.of(2026, 11, 26, 9, 0);
        for (int i = 0; i < 25; i++) {
            rows.add(WebhookDelivery.pending(issued.appId(), "evt_list-" + issued.appId() + "-" + i,
                    "transfer.completed", 10_000L + i, "{\"n\":" + i + "}", base.plusMinutes(i)));
        }
        deliveryRepository.saveAll(rows);

        mockMvc.perform(get(path(issued.appId()) + "/deliveries").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(20)))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[0].eventId").value("evt_list-" + issued.appId() + "-24"))   // 최신이 먼저
                .andExpect(jsonPath("$.items[0].type").value("transfer.completed"))
                .andExpect(jsonPath("$.items[0].transferId").value(10_024))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"))
                .andExpect(jsonPath("$.items[0].attemptCount").value(0))
                .andExpect(jsonPath("$.items[0].nextAttemptAt").exists())
                .andExpect(jsonPath("$.items[0].createdAt").exists())
                .andExpect(jsonPath("$.items[0].payload").doesNotExist());

        mockMvc.perform(get(path(issued.appId()) + "/deliveries?page=1").header("Authorization", bearer()))
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath("$.items[4].eventId").value("evt_list-" + issued.appId() + "-0"));

        mockMvc.perform(get(path(issued.appId()) + "/deliveries?size=500").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.items", hasSize(25)));

        mockMvc.perform(get(path(issued.appId()) + "/deliveries?size=0").header("Authorization", bearer()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(path(issued.appId()) + "/deliveries?page=-1").header("Authorization", bearer()))
                .andExpect(status().isBadRequest());
    }

    // ───────────── 도우미 ─────────────

    private ResultActions upsert(long appId, String url) throws Exception {
        return mockMvc.perform(put(path(appId)).header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + url + "\"}"));
    }

    private ResultActions getWebhook(long appId) throws Exception {
        return mockMvc.perform(get(path(appId)).header("Authorization", bearer()));
    }

    private static String path(long appId) {
        return "/api/v1/apps/" + appId + "/webhook";
    }

    private String bearer() {
        return "Bearer " + issued.developerToken();
    }
}
