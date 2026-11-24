package com.example.bank_open_api.account.api;

// [무엇] 오픈 API 계좌 조회(GET /open/v1/accounts/{번호}) 테스트 — 가짜 bank_core 사용
// [역할] 진짜 bank_core 없이, 진짜 포트로 뜨는 가짜 서버(FakeBankCore)를 상대로 끝까지 확인
//        - 정상 200 (우리 응답 모양: 계좌번호·상태·잔액만)
//        - bank_core 403 → 404, 404 → 404 (두 응답 본문이 똑같음)
//        - bank_core 500 → 502, 느림 → 504
//        - bank_core 401 → 재로그인 후 성공 / 새 토큰도 401이면 재시도는 한 번만 하고 502
//        - 토큰 재사용, 만료가 가까우면 미리 재로그인, 동시에 10개 요청이 와도 로그인은 1번
//        - API 키 없음 401 (bank_core까지 가지 않음), 계좌번호 형식 오류 400
//        - 로그에 비밀번호·bank_core 토큰·API 키 원문·계좌번호 전체가 없음
//        (bank_core가 꺼진 경우는 주소가 달라야 해서 BankCoreDownTest에서 따로 확인)
// [누가 쓰나] ./gradlew test 실행 시 JUnit이 실행 (Docker 필요: Testcontainers MySQL)

import com.example.bank_open_api.TestcontainersConfiguration;
import com.example.bank_open_api.bankcore.BankCoreAccount;
import com.example.bank_open_api.bankcore.BankCoreClient;
import com.example.bank_open_api.support.FakeBankCore;
import com.example.bank_open_api.support.OpenApiFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// bank-core.read-timeout=1s: 진짜 설정은 5초지만, 테스트가 오래 걸리지 않게 1초로 줄임 (동작 원리는 같음)
@SpringBootTest(properties = {
        "jwt.secret=test-only-jwt-secret-key-0123456789abcdef",
        "bank-core.username=" + BankCoreAccountTest.BANK_CORE_USERNAME,
        "bank-core.password=" + BankCoreAccountTest.BANK_CORE_PASSWORD,
        "bank-core.read-timeout=1s"
})
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class BankCoreAccountTest {

    // 테스트 전용 가짜 연동 계정 (진짜 값 아님)
    static final String BANK_CORE_USERNAME = "it-open-api";
    static final String BANK_CORE_PASSWORD = "it-fake-password-7Qz";

    private static final String ACCOUNT_NUMBER = "123-4567890";
    private static final String URL = "/open/v1/accounts/{accountNumber}";
    private static final String API_KEY_HEADER = "X-API-Key";

    // 가짜 bank_core는 테스트 클래스 전체에서 하나만 켠다 (Spring 설정보다 먼저 켜져야 주소를 넘길 수 있음)
    static final FakeBankCore FAKE = FakeBankCore.start(BANK_CORE_USERNAME, BANK_CORE_PASSWORD);

    // 서버 설정 bank-core.base-url에 가짜 서버 주소를 넣는다
    @DynamicPropertySource
    static void bankCoreProperties(DynamicPropertyRegistry registry) {
        registry.add("bank-core.base-url", FAKE::baseUrl);
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    BankCoreClient bankCoreClient;   // 동시 요청 테스트에서 여러 스레드가 직접 부름

    MockMvc mockMvc;
    OpenApiFixture.Issued issued;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        issued = OpenApiFixture.issueApiKey(mockMvc);
        FAKE.reset();
    }

    // ───────────── 정상 ─────────────

    @Test
    void 정상이면_200과_우리_응답_모양으로_돌려준다() throws Exception {
        FAKE.setBalance(123_456);

        getAccount()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value(ACCOUNT_NUMBER))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.balance").value(123_456))
                // bank_core 응답을 그대로 넘기지 않음: 예금주 이름·계좌 종류는 빠짐
                .andExpect(jsonPath("$.ownerName").doesNotExist())
                .andExpect(jsonPath("$.accountType").doesNotExist());

        // bank_core에는 연동 회원 토큰이 붙어서 갔고, 핀테크 앱의 API 키는 넘어가지 않았다
        assertThat(FAKE.lastAuthorization()).startsWith("Bearer fake-jwt-");
        assertThat(FAKE.lastAuthorization()).doesNotContain(issued.apiKey());
    }

    // ───────────── bank_core 오류 → 우리 오류 ─────────────

    @Test
    void bank_core_403과_404는_둘_다_404_ACCOUNT_NOT_FOUND이고_본문이_같다() throws Exception {
        FAKE.respondWithStatus(403);
        String forbidden = getAccount()
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        FAKE.respondWithStatus(404);
        String notFound = getAccount()
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // 응답만 보고는 "남의 계좌(403)"와 "없는 계좌(404)"를 구분할 수 없다
        assertThat(forbidden).isEqualTo(notFound);
        // 응답에 계좌번호도 넣지 않는다
        assertThat(notFound).doesNotContain(ACCOUNT_NUMBER);
    }

    @Test
    void bank_core_500이면_502_BANK_CORE_UNAVAILABLE() throws Exception {
        FAKE.respondWithStatus(500);
        getAccount()
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("BANK_CORE_UNAVAILABLE"));
    }

    @Test
    void bank_core가_느리면_504_BANK_CORE_TIMEOUT() throws Exception {
        FAKE.respondSlowly(2_500);   // 응답 제한 시간(테스트 1초)보다 오래 걸림

        long start = System.nanoTime();
        getAccount()
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("BANK_CORE_TIMEOUT"));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // 2.5초를 다 기다리지 않고 제한 시간 근처에서 끊었다
        assertThat(elapsedMillis).isLessThan(2_500);
    }

    // ───────────── 토큰 재사용·재로그인 ─────────────

    @Test
    void bank_core_토큰은_재사용한다() throws Exception {
        getAccount().andExpect(status().isOk());   // 필요하면 여기서 로그인
        int loginsAfterFirst = FAKE.loginCount();

        getAccount().andExpect(status().isOk());
        getAccount().andExpect(status().isOk());

        // 두 번째·세 번째 요청은 로그인 없이 같은 토큰을 씀
        assertThat(FAKE.loginCount()).isEqualTo(loginsAfterFirst);
    }

    @Test
    void bank_core가_401을_주면_다시_로그인해서_한_번_재시도하고_성공한다() throws Exception {
        getAccount().andExpect(status().isOk());
        FAKE.reset();
        FAKE.invalidateAllTokens();   // bank_core 재시작 등으로 우리가 가진 토큰이 무효가 됨

        getAccount()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value(ACCOUNT_NUMBER));

        assertThat(FAKE.unauthorizedCount()).isEqualTo(1);   // 옛 토큰이 한 번 거절됨
        assertThat(FAKE.loginCount()).isEqualTo(1);          // 다시 로그인 1번
        assertThat(FAKE.accountCallCount()).isEqualTo(2);    // 처음 + 재시도
    }

    @Test
    void 새_토큰도_401이면_재시도는_한_번만_하고_502() throws Exception {
        getAccount().andExpect(status().isOk());
        FAKE.reset();
        FAKE.alwaysUnauthorized();

        getAccount()
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("BANK_CORE_UNAVAILABLE"));

        // 무한 반복하지 않음: 조회 2번(처음 + 재시도 1번), 로그인 1번
        assertThat(FAKE.accountCallCount()).isEqualTo(2);
        assertThat(FAKE.loginCount()).isEqualTo(1);
    }

    @Test
    void 토큰_만료가_가까우면_401을_받기_전에_미리_다시_로그인한다() throws Exception {
        FAKE.setExpiresIn(10);        // 유효 10초짜리 토큰 → 30초 여유보다 짧아 곧바로 "만료 임박"
        FAKE.invalidateAllTokens();
        getAccount().andExpect(status().isOk());   // 401 → 재로그인 (이때 받은 토큰이 10초짜리)
        FAKE.reset();

        getAccount().andExpect(status().isOk());

        // 이번에는 401 없이 바로 새로 로그인함
        assertThat(FAKE.unauthorizedCount()).isZero();
        assertThat(FAKE.loginCount()).isEqualTo(1);
    }

    @Test
    void 동시에_10개_요청이_와도_bank_core_로그인은_1번만_한다() throws Exception {
        getAccount().andExpect(status().isOk());
        FAKE.reset();
        FAKE.invalidateAllTokens();       // 10개 모두 옛 토큰으로 401을 받게 됨
        FAKE.setLoginDelayMillis(300);    // 로그인이 걸리는 동안 다른 요청들이 몰려들게 함

        int requests = 10;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<BankCoreAccount>> results = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return bankCoreClient.getAccount(issued.appId(), ACCOUNT_NUMBER);
            }));
        }
        start.countDown();   // 한꺼번에 출발

        for (Future<BankCoreAccount> result : results) {
            assertThat(result.get(30, TimeUnit.SECONDS).accountNumber()).isEqualTo(ACCOUNT_NUMBER);
        }
        pool.shutdown();

        assertThat(FAKE.loginCount()).isEqualTo(1);
    }

    // ───────────── 우리 쪽에서 먼저 막는 경우 ─────────────

    @Test
    void API_키가_없으면_401이고_bank_core까지_가지_않는다() throws Exception {
        mockMvc.perform(get(URL, ACCOUNT_NUMBER))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_API_KEY"));

        assertThat(FAKE.accountCallCount()).isZero();
        assertThat(FAKE.loginCount()).isZero();
    }

    @Test
    void 계좌번호_형식이_틀리면_400이고_bank_core까지_가지_않는다() throws Exception {
        mockMvc.perform(get(URL, "100_0001").header(API_KEY_HEADER, issued.apiKey()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(FAKE.accountCallCount()).isZero();
    }

    // ───────────── 로그 ─────────────

    @Test
    void 로그에는_appId_끝4자리_상태코드_시간만_있고_비밀값은_없다(CapturedOutput output) throws Exception {
        FAKE.invalidateAllTokens();   // 재로그인까지 일어나게 해서 로그인 로그도 함께 검사
        getAccount().andExpect(status().isOk());
        FAKE.respondWithStatus(403);
        getAccount().andExpect(status().isNotFound());
        FAKE.respondWithStatus(500);
        getAccount().andExpect(status().isBadGateway());

        String logs = output.getAll();

        // 남아야 하는 것
        assertThat(logs).contains("appId=" + issued.appId());
        assertThat(logs).contains("accountLast4=7890");
        assertThat(logs).contains("status=200").contains("status=403").contains("status=500").contains("status=401");
        assertThat(logs).contains("elapsedMs=");
        assertThat(logs).contains("bank_core 로그인 성공");

        // 남으면 안 되는 것
        assertThat(logs).doesNotContain(BANK_CORE_PASSWORD);   // bank_core 비밀번호
        assertThat(logs).doesNotContain("fake-jwt-");          // bank_core 토큰
        assertThat(logs).doesNotContain(issued.apiKey());      // API 키 원문
        assertThat(logs).doesNotContain(ACCOUNT_NUMBER);       // 계좌번호 전체
    }

    // ───────────── 도우미 ─────────────

    private ResultActions getAccount() throws Exception {
        return mockMvc.perform(get(URL, ACCOUNT_NUMBER).header(API_KEY_HEADER, issued.apiKey()));
    }
}
