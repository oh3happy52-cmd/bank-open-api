package com.example.bank_open_api.mockfintech;

// [무엇] 웹훅 끝까지 테스트: 이체 → 아웃박스 → 발송 일꾼 → (진짜 HTTP) → 모의 핀테크 서버
// [역할] 서버를 진짜 포트로 띄우고(RANDOM_PORT), 웹훅 주소를 같은 서버의 /mock-fintech/webhooks로 등록한다
//        - 정상: SUCCEEDED, 모의 서버가 서명 검사를 통과시켜 1건 기록
//        - failRate=1.0: 6번 재시도 후 FAILED, 모의 서버 기록 0건
//        - failAfterProcessing: 보내는 쪽은 실패로 보고 재전송하지만 모의 서버 기록은 1건 (중복으로 걸러짐)
//          → 장애를 끄면 다음 재전송에서 SUCCEEDED
//        - 비밀키를 모의 서버에 안 넣었거나 다른 키면 401로 실패 기록
// [누가 쓰나] ./gradlew test (Docker 필요). 시간은 고정 시계(MutableClock), 일꾼은 직접 호출

import com.example.bank_open_api.TestcontainersConfiguration;
import com.example.bank_open_api.support.FakeBankCore;
import com.example.bank_open_api.support.MutableClock;
import com.example.bank_open_api.support.OpenApiFixture;
import com.example.bank_open_api.support.TestClockConfiguration;
import com.example.bank_open_api.webhook.application.WebhookDispatcher;
import com.example.bank_open_api.webhook.domain.DeliveryStatus;
import com.example.bank_open_api.webhook.domain.WebhookDelivery;
import com.example.bank_open_api.webhook.domain.WebhookDeliveryRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "jwt.secret=test-only-jwt-secret-key-0123456789abcdef",
        "bank-core.username=it-open-api",
        "bank-core.password=it-fake-password-7Qz",
        "mock-fintech.enabled=true"
})
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
class MockFintechEndToEndTest {

    static final FakeBankCore BANK_CORE = FakeBankCore.start("it-open-api", "it-fake-password-7Qz");

    @DynamicPropertySource
    static void bankCoreProperties(DynamicPropertyRegistry registry) {
        registry.add("bank-core.base-url", BANK_CORE::baseUrl);
    }

    @LocalServerPort int port;
    @Autowired WebApplicationContext context;
    @Autowired WebhookDispatcher dispatcher;
    @Autowired WebhookDeliveryRepository deliveryRepository;
    @Autowired MockFintechReceiver mockFintech;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MutableClock clock;

    MockMvc mockMvc;
    OpenApiFixture.Issued issued;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        BANK_CORE.reset();
        clock.set(TestClockConfiguration.INITIAL_TIME);
        jdbcTemplate.update("UPDATE webhook_deliveries SET status = 'CANCELED' WHERE status = 'PENDING'");
        mockFintech.clearReceived();
        mockFintech.updateFaults(new MockFintechReceiver.Faults(0, 0, false));
        issued = OpenApiFixture.issueApiKey(mockMvc);
    }

    @Test
    void 정상이면_모의_서버가_서명을_확인하고_1건_기록하며_SUCCEEDED() throws Exception {
        connectMockFintech();
        long transferId = transfer();

        assertThat(dispatcher.dispatchDue()).isEqualTo(1);

        WebhookDelivery delivery = onlyDelivery();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SUCCEEDED);
        assertThat(delivery.getLastStatusCode()).isEqualTo((short) 200);
        List<MockFintechReceiver.ReceivedEvent> received = mockFintech.received();
        assertThat(received).hasSize(1);
        assertThat(received.getFirst().eventId()).isEqualTo(delivery.getEventId());
        assertThat(received.getFirst().transferId()).isEqualTo(transferId);
        assertThat(received.getFirst().duplicateCount()).isZero();
    }

    @Test
    void failRate가_1이면_6번_재시도_후_FAILED이고_모의_서버에는_기록이_없다() throws Exception {
        connectMockFintech();
        transfer();
        mockFintech.updateFaults(new MockFintechReceiver.Faults(1.0, 0, false));

        for (int attempt = 1; attempt <= 6; attempt++) {
            assertThat(dispatcher.dispatchDue()).isEqualTo(1);
            clock.set(onlyDelivery().getNextAttemptAt());   // 다음 시도 시각으로 이동
        }

        WebhookDelivery delivery = onlyDelivery();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(delivery.getAttemptCount()).isEqualTo(6);
        assertThat(delivery.getLastStatusCode()).isEqualTo((short) 500);
        assertThat(mockFintech.received()).isEmpty();
    }

    @Test
    void failAfterProcessing이면_재전송되지만_기록은_1건이고_장애를_끄면_SUCCEEDED() throws Exception {
        connectMockFintech();
        transfer();
        mockFintech.updateFaults(new MockFintechReceiver.Faults(0, 0, true));

        dispatcher.dispatchDue();   // 1번째: 모의 서버는 기록하고 500
        clock.set(onlyDelivery().getNextAttemptAt());
        dispatcher.dispatchDue();   // 2번째(재전송): 중복으로 걸러지고 500
        assertThat(onlyDelivery().getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(onlyDelivery().getAttemptCount()).isEqualTo(2);

        mockFintech.updateFaults(new MockFintechReceiver.Faults(0, 0, false));
        clock.set(onlyDelivery().getNextAttemptAt());
        dispatcher.dispatchDue();   // 3번째: 중복이라 처리 안 하고 200

        assertThat(onlyDelivery().getStatus()).isEqualTo(DeliveryStatus.SUCCEEDED);
        assertThat(onlyDelivery().getAttemptCount()).isEqualTo(3);
        assertThat(mockFintech.received()).hasSize(1);
        assertThat(mockFintech.received().getFirst().duplicateCount()).isEqualTo(2);
    }

    @Test
    void 모의_서버에_다른_비밀키가_들어_있으면_401로_실패를_기록한다() throws Exception {
        registerWebhook();
        mockFintech.configureSecret("whsec_not-the-right-secret");
        transfer();

        dispatcher.dispatchDue();

        WebhookDelivery delivery = onlyDelivery();
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(delivery.getLastStatusCode()).isEqualTo((short) 401);
        assertThat(mockFintech.received()).isEmpty();
    }

    // ───────────── 도우미 ─────────────

    // 웹훅 주소를 이 서버의 모의 핀테크로 등록하고, 받은 비밀키를 모의 서버에 넣는다 (사람이 curl로 하는 순서와 같음)
    private void connectMockFintech() throws Exception {
        String secret = registerWebhook();
        mockMvc.perform(put("/mock-fintech/config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"secret\":\"" + secret + "\"}")).andExpect(status().isOk());
    }

    private String registerWebhook() throws Exception {
        String url = "http://127.0.0.1:" + port + "/mock-fintech/webhooks";
        String body = mockMvc.perform(put("/api/v1/apps/" + issued.appId() + "/webhook")
                        .header("Authorization", "Bearer " + issued.developerToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + url + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.secret");
    }

    private long transfer() throws Exception {
        String body = mockMvc.perform(post("/open/v1/transfers")
                        .header("X-API-Key", issued.apiKey())
                        .header("Idempotency-Key", "e2e-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountNumber\":\"200-0001\",\"toAccountNumber\":\"200-0002\",\"amount\":1000}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(body, "$.transferId")).longValue();
    }

    private WebhookDelivery onlyDelivery() {
        List<WebhookDelivery> rows = deliveryRepository.findAllByAppIdOrderByIdAsc(issued.appId());
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }
}
