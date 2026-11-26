package com.example.bank_open_api.usage;

// [무엇] 사용량 통계 API 테스트 (GET /api/v1/apps/{appId}/usage) — 진짜 MySQL
// [역할] 기록을 정해 둔 시각으로 DB에 직접 넣고(요청으로 만들면 시각을 고르기 어려움), 통계 결과를 확인
//        - daily: from~to 모든 날짜가 나오고 빈 날은 0, 상태 코드 분류(2xx / 4xx(429 제외) / 429 / 5xx)
//        - 날짜 경계: 전날 23:59:59.999와 다음 날 00:00:00은 빠지고, 마지막 날 23:59:59.999는 들어간다
//        - byEndpoint: 주소 틀별 합계, 많이 불린 순
//        - 기간 생략 시 오늘(고정 시계 2026-11-26) 포함 최근 7일 / 한쪽만 주면 나머지를 채움
//        - 다른 앱의 기록은 섞이지 않음
//        - 400: from > to, 31일 초과, 날짜 형식 오류 / 31일은 허용
//        - 404: 남의 앱, 없는 앱 (본문 동일) / 401: 토큰 없음
// [누가 쓰나] ./gradlew test 실행 시 JUnit이 실행 (Docker 필요)

import com.example.bank_open_api.TestcontainersConfiguration;
import com.example.bank_open_api.apikey.application.ApiKeyHasher;
import com.example.bank_open_api.apikey.domain.ApiKeyRepository;
import com.example.bank_open_api.support.OpenApiFixture;
import com.example.bank_open_api.support.TestClockConfiguration;
import com.example.bank_open_api.usage.domain.ApiUsageLog;
import com.example.bank_open_api.usage.domain.ApiUsageLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "jwt.secret=test-only-jwt-secret-key-0123456789abcdef")
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})   // 오늘 = 2026-11-26 (한국 시간)
class UsageStatsTest {

    private static final String PING = "/open/v1/ping";
    private static final String ACCOUNTS = "/open/v1/accounts/{accountNumber}";

    @Autowired
    WebApplicationContext context;

    @Autowired
    ApiUsageLogRepository usageLogRepository;

    @Autowired
    ApiKeyRepository apiKeyRepository;

    MockMvc mockMvc;

    // 테스트마다 새 개발자·앱·키
    OpenApiFixture.Issued issued;
    long keyId;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        issued = OpenApiFixture.issueApiKey(mockMvc);
        keyId = keyIdOf(issued.apiKey());
    }

    // ───────────── daily ─────────────

    @Test
    void 날짜별로_분류해_세고_기록_없는_날은_0으로_채운다() throws Exception {
        List<ApiUsageLog> logs = new ArrayList<>();
        // 11-20: 200, 201(2xx), 404, 400, 429, 429, 502, 504
        for (int code : new int[]{200, 201, 404, 400, 429, 429, 502, 504}) {
            logs.add(log(issued.appId(), keyId, ACCOUNTS, code, at(20, 10, 0)));
        }
        // 11-23: 200 하나
        logs.add(log(issued.appId(), keyId, PING, 200, at(23, 8, 0)));
        usageLogRepository.saveAll(logs);

        usage(issued.appId(), "?from=2026-11-20&to=2026-11-26")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appId").value(issued.appId()))
                .andExpect(jsonPath("$.from").value("2026-11-20"))
                .andExpect(jsonPath("$.to").value("2026-11-26"))
                .andExpect(jsonPath("$.daily", hasSize(7)))
                .andExpect(jsonPath("$.daily[*].date").value(contains(
                        "2026-11-20", "2026-11-21", "2026-11-22", "2026-11-23",
                        "2026-11-24", "2026-11-25", "2026-11-26")))
                // 11-20
                .andExpect(jsonPath("$.daily[0].total").value(8))
                .andExpect(jsonPath("$.daily[0].success").value(2))
                .andExpect(jsonPath("$.daily[0].clientError").value(2))   // 404, 400 (429는 제외)
                .andExpect(jsonPath("$.daily[0].rateLimited").value(2))
                .andExpect(jsonPath("$.daily[0].serverError").value(2))   // 502, 504
                // 11-21: 기록 없음 → 0
                .andExpect(jsonPath("$.daily[1].total").value(0))
                .andExpect(jsonPath("$.daily[1].success").value(0))
                .andExpect(jsonPath("$.daily[1].clientError").value(0))
                .andExpect(jsonPath("$.daily[1].rateLimited").value(0))
                .andExpect(jsonPath("$.daily[1].serverError").value(0))
                // 11-23
                .andExpect(jsonPath("$.daily[3].total").value(1))
                .andExpect(jsonPath("$.daily[3].success").value(1));
    }

    @Test
    void 날짜_경계는_한국_시간_자정_기준이고_마지막_날_끝까지_포함한다() throws Exception {
        usageLogRepository.saveAll(List.of(
                log(issued.appId(), keyId, PING, 200, LocalDateTime.of(2026, 11, 19, 23, 59, 59, 999_000_000)), // 범위 밖
                log(issued.appId(), keyId, PING, 200, LocalDateTime.of(2026, 11, 20, 0, 0, 0)),                 // 첫날 시작
                log(issued.appId(), keyId, PING, 200, LocalDateTime.of(2026, 11, 26, 0, 30)),                   // UTC로는 11-25
                log(issued.appId(), keyId, PING, 200, LocalDateTime.of(2026, 11, 26, 23, 59, 59, 999_000_000)), // 마지막 날 끝
                log(issued.appId(), keyId, PING, 200, LocalDateTime.of(2026, 11, 27, 0, 0, 0))                  // 범위 밖
        ));

        usage(issued.appId(), "?from=2026-11-20&to=2026-11-26")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daily[0].total").value(1))   // 11-20
                .andExpect(jsonPath("$.daily[5].total").value(0))   // 11-25 (00:30 KST 기록은 여기 아님)
                .andExpect(jsonPath("$.daily[6].total").value(2))   // 11-26
                .andExpect(jsonPath("$.byEndpoint[0].total").value(3));
    }

    // ───────────── byEndpoint ─────────────

    @Test
    void 주소_틀별로_기간_전체를_합쳐_많이_불린_순으로_보여_준다() throws Exception {
        usageLogRepository.saveAll(List.of(
                log(issued.appId(), keyId, ACCOUNTS, 200, at(21, 9, 0)),
                log(issued.appId(), keyId, ACCOUNTS, 404, at(22, 9, 0)),
                log(issued.appId(), keyId, ACCOUNTS, 429, at(24, 9, 0)),
                log(issued.appId(), keyId, PING, 200, at(25, 9, 0)),
                log(issued.appId(), keyId, "UNMATCHED", 404, at(25, 9, 1)),
                log(issued.appId(), keyId, "UNMATCHED", 405, at(25, 9, 2))
        ));

        usage(issued.appId(), "?from=2026-11-20&to=2026-11-26")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byEndpoint", hasSize(3)))
                .andExpect(jsonPath("$.byEndpoint[0].endpoint").value(ACCOUNTS))
                .andExpect(jsonPath("$.byEndpoint[0].total").value(3))
                .andExpect(jsonPath("$.byEndpoint[0].success").value(1))
                .andExpect(jsonPath("$.byEndpoint[0].clientError").value(1))
                .andExpect(jsonPath("$.byEndpoint[0].rateLimited").value(1))
                .andExpect(jsonPath("$.byEndpoint[0].serverError").value(0))
                .andExpect(jsonPath("$.byEndpoint[1].endpoint").value("UNMATCHED"))   // 2건
                .andExpect(jsonPath("$.byEndpoint[1].total").value(2))
                .andExpect(jsonPath("$.byEndpoint[2].endpoint").value(PING))          // 1건
                .andExpect(jsonPath("$.byEndpoint[2].total").value(1));
    }

    @Test
    void 기록이_하나도_없으면_daily는_전부_0이고_byEndpoint는_빈_배열() throws Exception {
        usage(issued.appId(), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daily", hasSize(7)))
                .andExpect(jsonPath("$.daily[*].total").value(contains(0, 0, 0, 0, 0, 0, 0)))
                .andExpect(jsonPath("$.byEndpoint", hasSize(0)));
    }

    @Test
    void 다른_앱의_기록은_섞이지_않는다() throws Exception {
        OpenApiFixture.Issued other = OpenApiFixture.issueApiKey(mockMvc);
        long otherKeyId = keyIdOf(other.apiKey());
        usageLogRepository.saveAll(List.of(
                log(issued.appId(), keyId, PING, 200, at(26, 9, 0)),
                log(other.appId(), otherKeyId, PING, 200, at(26, 9, 0)),
                log(other.appId(), otherKeyId, PING, 500, at(26, 9, 1))
        ));

        usage(issued.appId(), "")
                .andExpect(jsonPath("$.daily[6].total").value(1))
                .andExpect(jsonPath("$.daily[6].serverError").value(0));
    }

    // ───────────── 기간 ─────────────

    @Test
    void 기간을_생략하면_오늘_포함_최근_7일() throws Exception {
        usage(issued.appId(), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-11-20"))
                .andExpect(jsonPath("$.to").value("2026-11-26"))
                .andExpect(jsonPath("$.daily", hasSize(7)));
    }

    @Test
    void 한쪽만_주면_to는_오늘_from은_to의_6일_전으로_채운다() throws Exception {
        usage(issued.appId(), "?from=2026-11-24")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-11-24"))
                .andExpect(jsonPath("$.to").value("2026-11-26"))
                .andExpect(jsonPath("$.daily", hasSize(3)));

        usage(issued.appId(), "?to=2026-11-10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-11-04"))
                .andExpect(jsonPath("$.to").value("2026-11-10"))
                .andExpect(jsonPath("$.daily", hasSize(7)));
    }

    @Test
    void 하루짜리_기간과_31일_기간은_허용된다() throws Exception {
        usage(issued.appId(), "?from=2026-11-26&to=2026-11-26")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daily", hasSize(1)));

        usage(issued.appId(), "?from=2026-11-01&to=2026-12-01")   // 11-01 ~ 12-01 = 31일
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daily", hasSize(31)));
    }

    @Test
    void from이_to보다_늦거나_31일을_넘거나_형식이_틀리면_400() throws Exception {
        usage(issued.appId(), "?from=2026-11-26&to=2026-11-25")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));

        usage(issued.appId(), "?from=2026-11-01&to=2026-12-02")   // 32일
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));

        usage(issued.appId(), "?from=2026/11/20")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // ───────────── 권한 ─────────────

    @Test
    void 남의_앱과_없는_앱은_똑같이_404이고_기간이_틀려도_404가_먼저다() throws Exception {
        OpenApiFixture.Issued other = OpenApiFixture.issueApiKey(mockMvc);

        String othersApp = usage(other.appId(), "")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APP_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String missingApp = usage(999_999_999L, "")
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(othersApp).isEqualTo(missingApp);

        // 남의 앱 번호에 잘못된 기간을 줘도 400이 아니라 404 (앱 존재 여부 힌트를 주지 않음)
        usage(other.appId(), "?from=2026-11-26&to=2026-11-01")
                .andExpect(status().isNotFound());
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(get("/api/v1/apps/{appId}/usage", issued.appId()))
                .andExpect(status().isUnauthorized());
    }

    // ───────────── 도우미 ─────────────

    // 로그인한 개발자(issued의 주인) 토큰으로 통계 조회
    private ResultActions usage(long appId, String query) throws Exception {
        return mockMvc.perform(get("/api/v1/apps/" + appId + "/usage" + query)
                .header("Authorization", "Bearer " + issued.developerToken()));
    }

    // 2026-11-{day} {hour}:{minute} (한국 시간)
    private static LocalDateTime at(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 11, day, hour, minute);
    }

    private static ApiUsageLog log(long appId, long keyId, String endpoint, int status, LocalDateTime at) {
        return new ApiUsageLog(appId, keyId, "GET", endpoint, status, 5, at);
    }

    private long keyIdOf(String rawKey) {
        return apiKeyRepository.findByKeyHash(ApiKeyHasher.sha256Hex(rawKey)).orElseThrow().getId();
    }
}
