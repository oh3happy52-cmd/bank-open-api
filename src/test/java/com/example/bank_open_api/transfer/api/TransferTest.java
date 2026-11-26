package com.example.bank_open_api.transfer.api;

// [무엇] 오픈 API 이체 전달 테스트 (POST /open/v1/transfers) — 진짜 MySQL·Redis + 가짜 bank_core
// [역할] 1. 정상 201 + bank_core에 "app-{appId}:키"로 전달
//        2. 다른 앱 2개가 같은 키 → bank_core에 서로 다른 키로 전달 (둘 다 새 이체)
//        3. 같은 앱 같은 키 재요청 → 같은 transferId 201, 돈은 한 번만 옮겨짐
//        4. bank_core 403과 404가 글자 하나까지 같은 404 응답
//        5. 400·409·422 code 전달, 모르는 code, 5xx → 502, 약속에 없는 상태 → 502 + ERROR, 타임아웃 → 504 + 재시도 안내
//        6. bank_core 401 → 재로그인 후 같은 키로 재시도
//        7. Idempotency-Key 없음·65자·허용 안 된 문자, 본문 형식 오류(amount 0 등) → 400, bank_core 호출 안 함
//        8. 로그·응답에 계좌번호 전체가 없음 (bank_core 오류 메시지 원문도 응답에 없음)
//        9. 이체에도 호출 제한·사용량 기록 적용 (주소 틀 /open/v1/transfers)
//        + 64자 키(bank_core용으로 바꾸면 64자 초과)도 지문으로 바꿔 정상 처리, 재요청도 같은 결과
// [누가 쓰나] ./gradlew test (Docker 필요)
//
// rate-limit.capacity=5, refill 0.001/초: 한 앱이 한 테스트에서 5번까지 통과 (테스트마다 새 앱이라 서로 영향 없음)

import com.example.bank_open_api.TestcontainersConfiguration;
import com.example.bank_open_api.support.FakeBankCore;
import com.example.bank_open_api.support.OpenApiFixture;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "jwt.secret=test-only-jwt-secret-key-0123456789abcdef",
        "bank-core.username=it-open-api",
        "bank-core.password=it-fake-password-7Qz",
        "bank-core.read-timeout=1s",           // 504 테스트가 오래 걸리지 않게
        "rate-limit.capacity=5",
        "rate-limit.refill-per-second=0.001"
})
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class TransferTest {

    private static final String TRANSFERS = "/open/v1/transfers";
    // 로그·응답에 전체가 나오면 안 되는 계좌번호 (다른 곳에서 우연히 나올 수 없게 특이한 값)
    private static final String FROM = "201-7654321";
    private static final String TO = "301-1234987";

    static final FakeBankCore FAKE = FakeBankCore.start("it-open-api", "it-fake-password-7Qz");

    @DynamicPropertySource
    static void bankCoreProperties(DynamicPropertyRegistry registry) {
        registry.add("bank-core.base-url", FAKE::baseUrl);
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    ApiUsageLogRepository usageLogRepository;

    MockMvc mockMvc;
    OpenApiFixture.Issued issued;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        FAKE.reset();
        issued = OpenApiFixture.issueApiKey(mockMvc);
    }

    // ───────────── 1. 정상 ─────────────

    @Test
    void 정상이면_201과_우리_응답_모양이고_bank_core에는_app_번호를_붙인_키로_전달된다() throws Exception {
        String key = uniqueKey();

        transfer(issued.apiKey(), key, FROM, TO, 1_000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transferId").isNumber())
                .andExpect(jsonPath("$.fromAccountNumber").value(FROM))
                .andExpect(jsonPath("$.toAccountNumber").value(TO))
                .andExpect(jsonPath("$.amount").value(1_000))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.type").doesNotExist());   // bank_core 내부 칸은 내보내지 않음

        assertThat(FAKE.receivedIdempotencyKeys()).containsExactly("app-" + issued.appId() + ":" + key);
    }

    // ───────────── 2. 다른 앱의 같은 키 ─────────────

    @Test
    void 다른_앱이_같은_키를_써도_bank_core에는_서로_다른_키로_가서_각자_새_이체가_된다() throws Exception {
        OpenApiFixture.Issued other = OpenApiFixture.issueApiKey(mockMvc);
        String sameKey = uniqueKey();

        long first = transferId(transfer(issued.apiKey(), sameKey, FROM, TO, 1_000)
                .andExpect(status().isCreated()));
        // 내용까지 같아도(키가 충돌했다면 bank_core가 처음 결과를 돌려줬을 상황) 새 이체여야 한다
        long second = transferId(transfer(other.apiKey(), sameKey, FROM, TO, 1_000)
                .andExpect(status().isCreated()));

        assertThat(second).isNotEqualTo(first);
        assertThat(FAKE.createdTransferCount()).isEqualTo(2);
        assertThat(FAKE.receivedIdempotencyKeys()).containsExactly(
                "app-" + issued.appId() + ":" + sameKey,
                "app-" + other.appId() + ":" + sameKey);
    }

    // ───────────── 3. 같은 앱 같은 키 재요청 ─────────────

    @Test
    void 같은_앱이_같은_키로_다시_보내면_같은_transferId로_201이고_돈은_한_번만_옮겨진다() throws Exception {
        String key = uniqueKey();

        long first = transferId(transfer(issued.apiKey(), key, FROM, TO, 1_000).andExpect(status().isCreated()));
        long again = transferId(transfer(issued.apiKey(), key, FROM, TO, 1_000).andExpect(status().isCreated()));

        assertThat(again).isEqualTo(first);
        assertThat(FAKE.transferCallCount()).isEqualTo(2);
        assertThat(FAKE.createdTransferCount()).isEqualTo(1);
    }

    @Test
    void 같은_키에_다른_내용이면_422_IDEMPOTENCY_KEY_REUSED() throws Exception {
        String key = uniqueKey();
        transfer(issued.apiKey(), key, FROM, TO, 1_000).andExpect(status().isCreated());

        transfer(issued.apiKey(), key, FROM, TO, 2_000)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void 붙이면_64자를_넘는_64자_키도_지문으로_바꿔_처리하고_재요청은_같은_결과() throws Exception {
        String longKey = "k".repeat(64);

        long first = transferId(transfer(issued.apiKey(), longKey, FROM, TO, 1_000).andExpect(status().isCreated()));
        long again = transferId(transfer(issued.apiKey(), longKey, FROM, TO, 1_000).andExpect(status().isCreated()));

        assertThat(again).isEqualTo(first);
        assertThat(FAKE.receivedIdempotencyKeys()).hasSize(2)
                .allSatisfy(sent -> assertThat(sent)
                        .startsWith("app-" + issued.appId() + ":~")
                        .hasSizeLessThanOrEqualTo(64));
        assertThat(FAKE.receivedIdempotencyKeys().get(0)).isEqualTo(FAKE.receivedIdempotencyKeys().get(1));
    }

    // ───────────── 4. 403과 404 ─────────────

    @Test
    void bank_core_403과_404는_글자_하나까지_같은_404_ACCOUNT_NOT_FOUND() throws Exception {
        FAKE.respondTransferWith(403, "FORBIDDEN");
        String forbidden = transfer(issued.apiKey(), uniqueKey(), FROM, TO, 1_000)
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        FAKE.respondTransferWith(404, "ACCOUNT_NOT_FOUND");
        String notFound = transfer(issued.apiKey(), uniqueKey(), FROM, TO, 1_000)
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(forbidden).isEqualTo(notFound);
        assertThat(JsonPath.<String>read(forbidden, "$.code")).isEqualTo("ACCOUNT_NOT_FOUND");
        // bank_core 메시지(계좌번호가 섞여 있음)를 그대로 쓰지 않았다
        assertThat(forbidden).doesNotContain(TO).doesNotContain("가짜 bank_core");
    }

    // ───────────── 5. 오류 변환 ─────────────

    @Test
    void bank_core_400_409_422는_같은_상태와_같은_code로_우리_메시지와_함께_전달된다() throws Exception {
        FAKE.respondTransferWith(400, "SAME_ACCOUNT");
        expectError(400, "SAME_ACCOUNT", "같은 계좌로는 이체할 수 없습니다");

        FAKE.respondTransferWith(409, "DUPLICATE_REQUEST");
        expectError(409, "DUPLICATE_REQUEST", null);

        FAKE.respondTransferWith(422, "INSUFFICIENT_BALANCE");
        expectError(422, "INSUFFICIENT_BALANCE", "잔액이 부족합니다");

        FAKE.respondTransferWith(422, "ACCOUNT_NOT_ACTIVE");
        expectError(422, "ACCOUNT_NOT_ACTIVE", "사용할 수 없는 계좌입니다");
    }

    @Test
    void 모르는_code는_같은_상태의_기본_code로_응답하고_WARN을_남긴다(CapturedOutput output) throws Exception {
        FAKE.respondTransferWith(422, "SOMETHING_NEW");
        expectError(422, "TRANSFER_NOT_PROCESSABLE", "이체를 처리할 수 없습니다");

        FAKE.respondTransferWith(400, "SOMETHING_NEW_400");
        expectError(400, "INVALID_REQUEST", null);

        assertThat(output.getAll()).contains("bank_core 이체 오류 code를 모름").contains("code=SOMETHING_NEW");
    }

    @Test
    void bank_core_5xx는_502_BANK_CORE_UNAVAILABLE() throws Exception {
        FAKE.respondTransferWith(500, "INTERNAL_ERROR");
        expectError(502, "BANK_CORE_UNAVAILABLE", null);

        FAKE.respondTransferWith(503, "UNAVAILABLE");
        expectError(502, "BANK_CORE_UNAVAILABLE", null);
    }

    @Test
    void 약속에_없는_상태_코드는_502이고_ERROR_로그를_남긴다(CapturedOutput output) throws Exception {
        FAKE.respondTransferWith(418, "TEAPOT");
        expectError(502, "BANK_CORE_UNAVAILABLE", null);

        assertThat(output.getAll()).contains("ERROR").contains("bank_core 이체 응답이 약속과 다름").contains("status=418");
    }

    @Test
    void 타임아웃이면_504와_같은_키로_다시_요청하라는_안내이고_자동_재시도는_하지_않는다() throws Exception {
        FAKE.respondTransferSlowly(1_500);   // 응답 제한 시간(1초)보다 오래

        transfer(issued.apiKey(), uniqueKey(), FROM, TO, 1_000)
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("BANK_CORE_TIMEOUT"))
                .andExpect(jsonPath("$.message").value("처리 결과를 알 수 없습니다. 같은 Idempotency-Key로 다시 요청하세요"));

        // 결과를 모르는 채로 우리가 몰래 다시 보내지 않는다 (재요청은 앱이 같은 키로)
        assertThat(FAKE.transferCallCount()).isEqualTo(1);
    }

    // ───────────── 6. 401 → 재로그인 후 같은 키 ─────────────

    @Test
    void bank_core가_401을_주면_다시_로그인해서_같은_키로_한_번_재시도한다() throws Exception {
        String key = uniqueKey();
        transfer(issued.apiKey(), uniqueKey(), FROM, TO, 500).andExpect(status().isCreated());   // 토큰 받아 두기
        FAKE.reset();
        FAKE.invalidateAllTokens();   // bank_core가 재시작되어 갖고 있던 토큰이 무효가 된 상황

        transfer(issued.apiKey(), key, FROM, TO, 1_000).andExpect(status().isCreated());

        String expected = "app-" + issued.appId() + ":" + key;
        assertThat(FAKE.receivedIdempotencyKeys()).containsExactly(expected, expected);   // 401 → 같은 키로 재시도
        assertThat(FAKE.loginCount()).isEqualTo(1);
        assertThat(FAKE.createdTransferCount()).isEqualTo(1);
    }

    @Test
    void 새_토큰도_401이면_재시도는_한_번만_하고_502() throws Exception {
        FAKE.alwaysUnauthorized();

        expectError(502, "BANK_CORE_UNAVAILABLE", null);
        assertThat(FAKE.transferCallCount()).isEqualTo(2);
    }

    // ───────────── 7. 우리 쪽 형식 검사 ─────────────

    @Test
    void Idempotency_Key가_없거나_65자거나_허용_안_된_글자면_400이고_bank_core를_부르지_않는다() throws Exception {
        mockMvc.perform(post(TRANSFERS)
                        .header("X-API-Key", issued.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(FROM, TO, 1_000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));

        transfer(issued.apiKey(), "k".repeat(65), FROM, TO, 1_000)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));

        transfer(issued.apiKey(), "order:1", FROM, TO, 1_000)   // ':'는 앱 번호 구분자라 못 씀
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));

        transfer(issued.apiKey(), "order~1", FROM, TO, 1_000)   // '~'는 긴 키 지문 표시라 못 씀
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));

        transfer(issued.apiKey(), "order 1", FROM, TO, 1_000)   // 공백
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));

        assertThat(FAKE.transferCallCount()).isZero();
    }

    @Test
    void 금액이_0이거나_없거나_계좌번호_형식이_틀리면_400이고_bank_core를_부르지_않는다() throws Exception {
        transfer(issued.apiKey(), uniqueKey(), FROM, TO, 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("amount")));

        transfer(issued.apiKey(), uniqueKey(), FROM, TO, -100)
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(TRANSFERS)
                        .header("X-API-Key", issued.apiKey())
                        .header("Idempotency-Key", uniqueKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountNumber\":\"" + FROM + "\",\"toAccountNumber\":\"" + TO + "\"}"))
                .andExpect(status().isBadRequest());

        transfer(issued.apiKey(), uniqueKey(), "201/7654321", TO, 1_000)
                .andExpect(status().isBadRequest());

        assertThat(FAKE.transferCallCount()).isZero();
    }

    // ───────────── 8. 로그 ─────────────

    @Test
    void 로그에는_계좌번호_끝_4자리와_원래_키만_있고_계좌번호_전체와_토큰은_없다(CapturedOutput output) throws Exception {
        String key = uniqueKey();
        transfer(issued.apiKey(), key, FROM, TO, 1_000).andExpect(status().isCreated());
        FAKE.respondTransferWith(404, "ACCOUNT_NOT_FOUND");
        transfer(issued.apiKey(), uniqueKey(), FROM, TO, 1_000).andExpect(status().isNotFound());
        FAKE.respondTransferSlowly(1_500);
        transfer(issued.apiKey(), uniqueKey(), FROM, TO, 1_000).andExpect(status().isGatewayTimeout());

        String logs = output.getAll();
        assertThat(logs).contains("fromLast4=4321").contains("toLast4=4987");
        assertThat(logs).contains("idempotencyKey=" + key);
        assertThat(logs).contains("이체 성공");
        assertThat(logs).doesNotContain(FROM).doesNotContain(TO);
        assertThat(logs).doesNotContain(issued.apiKey());
        assertThat(logs).doesNotContain("fake-jwt-");
    }

    // ───────────── 9. 호출 제한·사용량 기록 ─────────────

    @Test
    void 이체에도_호출_제한과_사용량_기록이_적용되고_주소_틀은_open_v1_transfers() throws Exception {
        for (int i = 0; i < 5; i++) {
            transfer(issued.apiKey(), uniqueKey(), FROM, TO, 100).andExpect(status().isCreated());
        }
        transfer(issued.apiKey(), uniqueKey(), FROM, TO, 100)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));

        assertThat(FAKE.transferCallCount()).isEqualTo(5);   // 6번째는 bank_core까지 가지 않음

        await().atMost(Duration.ofSeconds(5))
                .until(() -> usageLogRepository.findAllByAppIdOrderByIdDesc(issued.appId()).size() >= 6);
        List<ApiUsageLog> logs = usageLogRepository.findAllByAppIdOrderByIdDesc(issued.appId());
        assertThat(logs).hasSize(6);
        assertThat(logs).extracting(ApiUsageLog::getEndpoint).containsOnly(TRANSFERS);
        assertThat(logs).extracting(ApiUsageLog::getMethod).containsOnly("POST");
        assertThat(logs).extracting(ApiUsageLog::getStatusCode)
                .containsExactlyInAnyOrder((short) 201, (short) 201, (short) 201, (short) 201, (short) 201, (short) 429);
    }

    // ───────────── 도우미 ─────────────

    private ResultActions transfer(String apiKey, String idempotencyKey, String from, String to, long amount)
            throws Exception {
        return mockMvc.perform(post(TRANSFERS)
                .header("X-API-Key", apiKey)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(from, to, amount)));
    }

    // 오류 응답 확인. message가 null이면 메시지는 확인하지 않고, bank_core 메시지(계좌번호 포함)가 아닌지만 본다
    private void expectError(int status, String code, String message) throws Exception {
        String body = transfer(issued.apiKey(), uniqueKey(), FROM, TO, 1_000)
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        if (message != null) {
            assertThat(JsonPath.<String>read(body, "$.message")).isEqualTo(message);
        }
        assertThat(body).doesNotContain(TO).doesNotContain("가짜 bank_core");
    }

    private static String body(String from, String to, long amount) {
        return "{\"fromAccountNumber\":\"" + from + "\",\"toAccountNumber\":\"" + to + "\",\"amount\":" + amount + "}";
    }

    private static long transferId(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(body, "$.transferId")).longValue();
    }

    private static String uniqueKey() {
        return "order-" + UUID.randomUUID();
    }
}
