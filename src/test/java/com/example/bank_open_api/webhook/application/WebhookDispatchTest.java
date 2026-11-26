package com.example.bank_open_api.webhook.application;

// [무엇] 웹훅 알림 저장(아웃박스) + 발송 일꾼 테스트 — 진짜 MySQL + 가짜 bank_core + 가짜 수신 서버 + 고정 시계
// [역할] 3. 이체 성공 → 알림 1줄, 같은 키 재요청 → 여전히 1줄, 웹훅 꺼짐·미등록 → 0줄
//        4. 발송 성공 → SUCCEEDED, 받은 쪽이 같은 비밀키로 계산한 서명 = 헤더, 서명한 본문 = 받은 본문
//        2. rotate 후에는 (재전송 포함) 새 키로 서명
//        5. 실패 → 2, 4, 8, 16, 32초 간격, 6번째 실패 → FAILED, 재전송해도 eventId 같음
//           타임아웃·연결 실패·3xx(리다이렉트 안 따라감)도 실패, 보낼 때 꺼져 있으면 CANCELED
//        6. 일꾼 2개를 동시에 돌려도 알림마다 정확히 1번만 전송 (SKIP LOCKED)
//        7. 서버 재시작: 남은 PENDING을 새 일꾼이 보냄, 꺼내 놓고 죽은 알림도 lease가 지나면 다시 보냄
//        8. 로그에 비밀키·서명값 없음
//        9. 스케줄러는 꺼져 있다 (테스트가 dispatchDue()를 직접 부름)
// [누가 쓰나] ./gradlew test (Docker 필요)

import com.example.bank_open_api.TestcontainersConfiguration;
import com.example.bank_open_api.support.FakeBankCore;
import com.example.bank_open_api.support.FakeWebhookReceiver;
import com.example.bank_open_api.support.MutableClock;
import com.example.bank_open_api.support.OpenApiFixture;
import com.example.bank_open_api.support.TestClockConfiguration;
import com.example.bank_open_api.webhook.config.WebhookDispatchScheduler;
import com.example.bank_open_api.webhook.crypto.WebhookSecretCipher;
import com.example.bank_open_api.webhook.domain.DeliveryStatus;
import com.example.bank_open_api.webhook.domain.WebhookDelivery;
import com.example.bank_open_api.webhook.domain.WebhookDeliveryRepository;
import com.example.bank_open_api.webhook.domain.WebhookEndpointRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "jwt.secret=test-only-jwt-secret-key-0123456789abcdef",
        "bank-core.username=it-open-api",
        "bank-core.password=it-fake-password-7Qz",
        // 타임아웃 테스트가 오래 걸리지 않게 줄임 (진짜 설정: 연결 2초, 응답 3초)
        "webhook.http.connect-timeout=300ms",
        "webhook.http.read-timeout=300ms"
})
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class WebhookDispatchTest {

    private static final String FROM = "200-0001";
    private static final String TO = "200-0002";

    static final FakeBankCore BANK_CORE = FakeBankCore.start("it-open-api", "it-fake-password-7Qz");
    static final FakeWebhookReceiver RECEIVER = FakeWebhookReceiver.start();

    @DynamicPropertySource
    static void bankCoreProperties(DynamicPropertyRegistry registry) {
        registry.add("bank-core.base-url", BANK_CORE::baseUrl);
    }

    @Autowired WebApplicationContext context;
    @Autowired ApplicationContext applicationContext;
    @Autowired WebhookDispatcher dispatcher;
    @Autowired WebhookDeliveryStore store;
    @Autowired WebhookDeliveryRepository deliveryRepository;
    @Autowired WebhookEndpointRepository endpointRepository;
    @Autowired WebhookSecretCipher cipher;
    @Autowired WebhookSender sender;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MutableClock clock;

    MockMvc mockMvc;
    OpenApiFixture.Issued issued;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        BANK_CORE.reset();
        RECEIVER.reset();
        clock.set(TestClockConfiguration.INITIAL_TIME);
        // 앞 테스트가 남긴 PENDING 알림이 이 테스트의 dispatchDue()에 섞이지 않게 정리
        jdbcTemplate.update("UPDATE webhook_deliveries SET status = 'CANCELED' WHERE status = 'PENDING'");
        issued = OpenApiFixture.issueApiKey(mockMvc);
    }

    // ───────────── 9. 스케줄러 꺼짐 ─────────────

    @Test
    void 테스트에서는_스케줄러가_꺼져_있다() {
        assertThat(applicationContext.getBeanNamesForType(WebhookDispatchScheduler.class)).isEmpty();
    }

    // ───────────── 3. 알림 저장 ─────────────

    @Test
    void 이체가_성공하면_알림_1줄이_PENDING으로_저장되고_payload는_그때_확정된다() throws Exception {
        registerWebhook(RECEIVER.url());
        long transferId = transfer(uniqueKey(), 1_000);

        List<WebhookDelivery> rows = deliveryRepository.findAllByAppIdOrderByIdAsc(issued.appId());
        assertThat(rows).hasSize(1);
        WebhookDelivery row = rows.getFirst();
        assertThat(row.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(row.getEventId()).matches("evt_[0-9a-f-]{36}");
        assertThat(row.getEventType()).isEqualTo("transfer.completed");
        assertThat(row.getTransferId()).isEqualTo(transferId);
        assertThat(row.getAttemptCount()).isZero();
        assertThat(row.getNextAttemptAt()).isEqualTo(TestClockConfiguration.INITIAL_TIME);

        String payload = row.getPayload();
        assertThat(JsonPath.<String>read(payload, "$.eventId")).isEqualTo(row.getEventId());
        assertThat(JsonPath.<String>read(payload, "$.type")).isEqualTo("transfer.completed");
        assertThat(JsonPath.<String>read(payload, "$.createdAt")).isEqualTo("2026-11-26T12:00:00+09:00");
        assertThat(((Number) JsonPath.read(payload, "$.data.transferId")).longValue()).isEqualTo(transferId);
        assertThat(JsonPath.<String>read(payload, "$.data.fromAccountNumber")).isEqualTo(FROM);
        assertThat(JsonPath.<String>read(payload, "$.data.toAccountNumber")).isEqualTo(TO);
        assertThat(((Number) JsonPath.read(payload, "$.data.amount")).longValue()).isEqualTo(1_000);
        assertThat(JsonPath.<String>read(payload, "$.data.status")).isEqualTo("COMPLETED");
    }

    @Test
    void 같은_키로_다시_보내_201이_또_와도_알림은_1줄() throws Exception {
        registerWebhook(RECEIVER.url());
        String key = uniqueKey();
        long first = transfer(key, 1_000);
        long again = transfer(key, 1_000);

        assertThat(again).isEqualTo(first);
        assertThat(deliveryRepository.findAllByAppIdOrderByIdAsc(issued.appId())).hasSize(1);
    }

    @Test
    void 웹훅이_꺼져_있거나_등록하지_않았으면_알림을_만들지_않는다() throws Exception {
        transfer(uniqueKey(), 1_000);   // 미등록
        registerWebhook(RECEIVER.url());
        mockMvc.perform(delete(webhookPath()).header("Authorization", bearer())).andExpect(status().isNoContent());
        transfer(uniqueKey(), 1_000);   // 꺼짐

        assertThat(deliveryRepository.findAllByAppIdOrderByIdAsc(issued.appId())).isEmpty();
    }

    // ───────────── 4. 발송 성공 + 서명 ─────────────

    @Test
    void 보내면_SUCCEEDED이고_받은_쪽이_같은_비밀키로_계산한_서명이_헤더와_같다() throws Exception {
        String secret = registerWebhook(RECEIVER.url());
        transfer(uniqueKey(), 1_000);
        WebhookDelivery before = onlyDelivery();

        assertThat(dispatcher.dispatchDue()).isEqualTo(1);

        assertThat(RECEIVER.received()).hasSize(1);
        FakeWebhookReceiver.Received got = RECEIVER.received().getFirst();
        long timestamp = clock.instant().getEpochSecond();
        assertThat(got.header("Content-Type")).startsWith("application/json");
        assertThat(got.header("X-Webhook-Id")).isEqualTo(before.getEventId());
        assertThat(got.header("X-Webhook-Timestamp")).isEqualTo(String.valueOf(timestamp));
        // 받는 쪽 입장에서 직접 계산 (WebhookSigner를 쓰지 않고 독립적으로)
        assertThat(got.header("X-Webhook-Signature")).isEqualTo(expectedSignature(secret, timestamp, got.body()));
        // 서명한 본문(= DB payload) 바이트와 실제로 받은 바이트가 정확히 같다
        assertThat(got.body()).isEqualTo(before.getPayload().getBytes(StandardCharsets.UTF_8));

        WebhookDelivery after = onlyDelivery();
        assertThat(after.getStatus()).isEqualTo(DeliveryStatus.SUCCEEDED);
        assertThat(after.getAttemptCount()).isEqualTo(1);
        assertThat(after.getLastStatusCode()).isEqualTo((short) 200);
        assertThat(after.getDeliveredAt()).isEqualTo(TestClockConfiguration.INITIAL_TIME);

        assertThat(dispatcher.dispatchDue()).isZero();   // 다시 돌려도 또 보내지 않음
        assertThat(RECEIVER.received()).hasSize(1);
    }

    @Test
    void 비밀키를_교체하면_재전송도_보내는_시점의_새_키로_서명한다() throws Exception {
        String oldSecret = registerWebhook(RECEIVER.url());
        transfer(uniqueKey(), 1_000);

        RECEIVER.respondWith(500);
        dispatcher.dispatchDue();   // 1번째: 옛 키로 서명, 실패
        String newSecret = rotateSecret();
        RECEIVER.respondWith(200);
        clock.set(TestClockConfiguration.INITIAL_TIME.plusSeconds(2));
        dispatcher.dispatchDue();   // 2번째(재전송): 새 키로 서명, 성공

        List<FakeWebhookReceiver.Received> got = RECEIVER.received();
        assertThat(got).hasSize(2);
        assertThat(verifies(oldSecret, got.get(0))).isTrue();
        assertThat(verifies(newSecret, got.get(1))).isTrue();
        assertThat(verifies(oldSecret, got.get(1))).isFalse();
        assertThat(onlyDelivery().getStatus()).isEqualTo(DeliveryStatus.SUCCEEDED);
    }

    // ───────────── 5. 실패와 재시도 ─────────────

    @Test
    void 실패하면_2_4_8_16_32초_뒤에_다시_보내고_6번째_실패면_FAILED이며_eventId는_그대로() throws Exception {
        registerWebhook(RECEIVER.url());
        transfer(uniqueKey(), 1_000);
        RECEIVER.respondWith(500);
        String eventId = onlyDelivery().getEventId();

        LocalDateTime now = TestClockConfiguration.INITIAL_TIME;
        long[] expectedGaps = {2, 4, 8, 16, 32};
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(dispatcher.dispatchDue()).isEqualTo(1);
            WebhookDelivery row = onlyDelivery();
            assertThat(row.getStatus()).isEqualTo(DeliveryStatus.PENDING);
            assertThat(row.getAttemptCount()).isEqualTo(attempt);
            assertThat(row.getLastStatusCode()).isEqualTo((short) 500);
            assertThat(row.getLastError()).isEqualTo("HTTP 500");
            assertThat(Duration.between(now, row.getNextAttemptAt()))
                    .isEqualTo(Duration.ofSeconds(expectedGaps[attempt - 1]));

            // 시각이 되기 1초 전에는 보내지 않는다
            clock.set(row.getNextAttemptAt().minusSeconds(1));
            assertThat(dispatcher.dispatchDue()).isZero();
            now = row.getNextAttemptAt();
            clock.set(now);
        }

        assertThat(dispatcher.dispatchDue()).isEqualTo(1);   // 6번째
        WebhookDelivery failed = onlyDelivery();
        assertThat(failed.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(failed.getAttemptCount()).isEqualTo(6);

        clock.set(now.plusHours(1));
        assertThat(dispatcher.dispatchDue()).isZero();   // FAILED는 더 보내지 않음

        assertThat(RECEIVER.received()).hasSize(6)
                .extracting(r -> r.header("X-Webhook-Id")).containsOnly(eventId);
    }

    @Test
    void 응답이_늦으면_TIMEOUT으로_실패_처리하고_2초_뒤_다시_보낸다() throws Exception {
        registerWebhook(RECEIVER.url());
        transfer(uniqueKey(), 1_000);
        RECEIVER.respondSlowly(1_000);   // 응답 제한 시간(테스트 300ms)보다 오래

        dispatcher.dispatchDue();

        WebhookDelivery row = onlyDelivery();
        assertThat(row.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(row.getAttemptCount()).isEqualTo(1);
        assertThat(row.getLastError()).isEqualTo("TIMEOUT");
        assertThat(row.getLastStatusCode()).isNull();
        assertThat(row.getNextAttemptAt()).isEqualTo(TestClockConfiguration.INITIAL_TIME.plusSeconds(2));
    }

    @Test
    void 연결_실패와_3xx도_실패이고_리다이렉트는_따라가지_않는다() throws Exception {
        registerWebhook("http://127.0.0.1:" + closedPort() + "/webhook");
        transfer(uniqueKey(), 1_000);
        dispatcher.dispatchDue();
        assertThat(onlyDelivery().getLastError()).isEqualTo("CONNECTION_FAILED");

        registerWebhook(RECEIVER.url());   // 주소만 바꿈 (비밀키 그대로)
        RECEIVER.respondWith(302);
        clock.set(TestClockConfiguration.INITIAL_TIME.plusSeconds(2));
        dispatcher.dispatchDue();
        WebhookDelivery row = onlyDelivery();
        assertThat(row.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(row.getLastStatusCode()).isEqualTo((short) 302);
        assertThat(row.getLastError()).isEqualTo("HTTP 302");
        assertThat(RECEIVER.received()).hasSize(1);   // 302를 따라 다른 곳으로 다시 보내지 않음
    }

    @Test
    void 보낼_때_웹훅이_꺼져_있으면_보내지_않고_CANCELED() throws Exception {
        registerWebhook(RECEIVER.url());
        transfer(uniqueKey(), 1_000);
        mockMvc.perform(delete(webhookPath()).header("Authorization", bearer())).andExpect(status().isNoContent());

        assertThat(dispatcher.dispatchDue()).isEqualTo(1);

        assertThat(RECEIVER.received()).isEmpty();
        assertThat(onlyDelivery().getStatus()).isEqualTo(DeliveryStatus.CANCELED);
    }

    // ───────────── 6. 일꾼 2개 동시 ─────────────

    @Test
    void 일꾼_2개를_동시에_돌려도_알림마다_정확히_1번만_보낸다() throws Exception {
        registerWebhook(RECEIVER.url());
        int total = 45;   // 한 번에 20개씩 → 여러 바퀴가 겹치게
        List<WebhookDelivery> rows = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            rows.add(WebhookDelivery.pending(issued.appId(), "evt_" + UUID.randomUUID(), "transfer.completed",
                    50_000L + i, "{\"n\":" + i + "}", TestClockConfiguration.INITIAL_TIME));
        }
        deliveryRepository.saveAll(rows);
        RECEIVER.respondSlowly(20);   // 보내는 동안 다른 일꾼이 같은 시각에 꺼내려 하도록 조금 느리게

        // 서버 두 대의 일꾼이라고 생각: 같은 DB를 보는 서로 다른 객체
        WebhookDispatcher serverA = dispatcher;
        WebhookDispatcher serverB = new WebhookDispatcher(store, endpointRepository, cipher, sender, clock);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = List.of(
                    pool.submit(() -> runUntilEmpty(serverA, start)),
                    pool.submit(() -> runUntilEmpty(serverB, start)));
            start.countDown();
            int handled = results.get(0).get() + results.get(1).get();
            assertThat(handled).isEqualTo(total);
        } finally {
            pool.shutdownNow();
        }

        List<String> ids = RECEIVER.received().stream().map(r -> r.header("X-Webhook-Id")).toList();
        assertThat(ids).hasSize(total).doesNotHaveDuplicates();
        assertThat(deliveryRepository.findAllByAppIdOrderByIdAsc(issued.appId()))
                .extracting(WebhookDelivery::getStatus).containsOnly(DeliveryStatus.SUCCEEDED);
    }

    // ───────────── 7. 서버 재시작 ─────────────

    @Test
    void 재시작한_서버의_새_일꾼이_DB에_남은_PENDING을_이어서_보낸다() throws Exception {
        registerWebhook(RECEIVER.url());
        transfer(uniqueKey(), 1_000);   // 알림이 DB에 저장된 뒤 서버가 꺼졌다고 가정

        WebhookDispatcher afterRestart = new WebhookDispatcher(store, endpointRepository, cipher, sender, clock);
        assertThat(afterRestart.dispatchDue()).isEqualTo(1);

        assertThat(RECEIVER.received()).hasSize(1);
        assertThat(onlyDelivery().getStatus()).isEqualTo(DeliveryStatus.SUCCEEDED);
    }

    @Test
    void 꺼내_놓고_보내기_전에_죽은_알림도_lease가_지나면_새_일꾼이_보낸다() throws Exception {
        registerWebhook(RECEIVER.url());
        transfer(uniqueKey(), 1_000);

        // 죽은 일꾼: 1단계(꺼내기)만 하고 사라짐
        assertThat(store.claimDue(LocalDateTime.now(clock))).hasSize(1);

        WebhookDispatcher afterRestart = new WebhookDispatcher(store, endpointRepository, cipher, sender, clock);
        assertThat(afterRestart.dispatchDue()).isZero();   // lease(2분) 동안은 아무도 다시 꺼내지 않음

        clock.set(TestClockConfiguration.INITIAL_TIME.plusMinutes(2));
        assertThat(afterRestart.dispatchDue()).isEqualTo(1);
        assertThat(RECEIVER.received()).hasSize(1);
        assertThat(onlyDelivery().getStatus()).isEqualTo(DeliveryStatus.SUCCEEDED);
    }

    // ───────────── 8. 로그 ─────────────

    @Test
    void 로그에는_비밀키와_서명값이_없다(CapturedOutput output) throws Exception {
        String secret = registerWebhook(RECEIVER.url());
        transfer(uniqueKey(), 1_000);
        RECEIVER.respondWith(500);
        dispatcher.dispatchDue();
        String rotated = rotateSecret();
        RECEIVER.respondWith(200);
        clock.set(TestClockConfiguration.INITIAL_TIME.plusSeconds(2));
        dispatcher.dispatchDue();

        String logs = output.getAll();
        assertThat(logs).contains("웹훅 전송 실패").contains("웹훅 전송 성공");
        assertThat(logs).doesNotContain(secret).doesNotContain(rotated)
                .doesNotContain(secret.substring(12)).doesNotContain(rotated.substring(12));
        for (FakeWebhookReceiver.Received got : RECEIVER.received()) {
            String signature = got.header("X-Webhook-Signature");
            assertThat(logs).doesNotContain(signature).doesNotContain(signature.substring(3));
        }
    }

    // ───────────── 도우미 ─────────────

    // 웹훅 등록(또는 주소 수정). 처음이면 비밀키 원문을 돌려줌, 수정이면 null
    private String registerWebhook(String url) throws Exception {
        String body = mockMvc.perform(put(webhookPath()).header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + url + "\"}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return body.contains("\"secret\"") ? JsonPath.read(body, "$.secret") : null;
    }

    private String rotateSecret() throws Exception {
        String body = mockMvc.perform(post(webhookPath() + "/secret/rotate").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.secret");
    }

    private long transfer(String key, long amount) throws Exception {
        String body = mockMvc.perform(post("/open/v1/transfers")
                        .header("X-API-Key", issued.apiKey())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountNumber\":\"" + FROM + "\",\"toAccountNumber\":\"" + TO
                                + "\",\"amount\":" + amount + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(body, "$.transferId")).longValue();
    }

    private WebhookDelivery onlyDelivery() {
        List<WebhookDelivery> rows = deliveryRepository.findAllByAppIdOrderByIdAsc(issued.appId());
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private static int runUntilEmpty(WebhookDispatcher worker, CountDownLatch start) throws InterruptedException {
        start.await();
        int handled = 0;
        int n;
        while ((n = worker.dispatchDue()) > 0) {
            handled += n;
        }
        return handled;
    }

    // 받는 쪽이 하는 검증: HMAC-SHA256(비밀키, 타임스탬프 + "." + 받은 본문)
    private static String expectedSignature(String secret, long timestamp, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
        return "v1=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    private static boolean verifies(String secret, FakeWebhookReceiver.Received got) throws Exception {
        long timestamp = Long.parseLong(got.header("X-Webhook-Timestamp"));
        return expectedSignature(secret, timestamp, got.body()).equals(got.header("X-Webhook-Signature"));
    }

    private String webhookPath() {
        return "/api/v1/apps/" + issued.appId() + "/webhook";
    }

    private String bearer() {
        return "Bearer " + issued.developerToken();
    }

    private static String uniqueKey() {
        return "wh-" + UUID.randomUUID();
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
