package com.example.bank_open_api.mockfintech;

// [무엇] 모의 핀테크 서버 "받는 쪽" 테스트 (MockMvc로 직접 웹훅을 보내 봄)
// [역할] - 올바른 서명 200 / 서명 틀림 401 / 본문 한 글자 바꿈 401 / 5분 넘게 지난(또는 앞선) 타임스탬프 401, 딱 5분은 200
//        - 같은 X-Webhook-Id 2번 → 기록 1개, 중복 횟수 1
//        - 비밀키 미설정 503, 헤더·본문 eventId 불일치 400
//        - 장애: failRate=1 → 500이고 기록 안 함 / failAfterProcessing → 기록은 하고 500
//        - 관리 API: 잘못된 장애 값 400, 초기화
//        - 로그에 비밀키·서명값 없음
// [누가 쓰나] ./gradlew test (Docker 필요: 스프링 전체가 뜨므로 MySQL·Redis)

import com.example.bank_open_api.TestcontainersConfiguration;
import com.example.bank_open_api.support.MutableClock;
import com.example.bank_open_api.support.TestClockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "jwt.secret=test-only-jwt-secret-key-0123456789abcdef",
        "mock-fintech.enabled=true"
})
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class MockFintechWebhookTest {

    private static final String SECRET = "whsec_test-only-secret-for-mock-fintech-0000000";

    @Autowired WebApplicationContext context;
    @Autowired MutableClock clock;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        clock.set(TestClockConfiguration.INITIAL_TIME);
        mockMvc.perform(put("/mock-fintech/config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"secret\":\"" + SECRET + "\"}")).andExpect(status().isOk());
        faults("{\"failRate\":0,\"delayMillis\":0,\"failAfterProcessing\":false}");
        mockMvc.perform(delete("/mock-fintech/received")).andExpect(status().isNoContent());
    }

    // ───────────── 서명·시각 ─────────────

    @Test
    void 올바른_서명이면_200이고_받은_목록에_기록된다() throws Exception {
        String eventId = newEventId();
        send(eventId, now(), body(eventId, 77), SECRET)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("RECEIVED"));

        mockMvc.perform(get("/mock-fintech/received"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].eventId").value(eventId))
                .andExpect(jsonPath("$[0].type").value("transfer.completed"))
                .andExpect(jsonPath("$[0].transferId").value(77))
                .andExpect(jsonPath("$[0].receivedAt").value("2026-11-26T12:00:00"))
                .andExpect(jsonPath("$[0].duplicateCount").value(0));
    }

    @Test
    void 다른_비밀키로_서명했거나_서명이_없으면_401() throws Exception {
        String eventId = newEventId();
        send(eventId, now(), body(eventId, 1), "whsec_wrong-secret")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.result").value("INVALID_SIGNATURE"));

        mockMvc.perform(post("/mock-fintech/webhooks").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Webhook-Id", eventId).header("X-Webhook-Timestamp", now())
                        .content(body(eventId, 1)))
                .andExpect(status().isUnauthorized());
        assertReceivedCount(0);
    }

    @Test
    void 서명한_뒤_본문을_한_글자만_바꿔도_401() throws Exception {
        String eventId = newEventId();
        String original = body(eventId, 1000);
        String signature = sign(SECRET, now(), original.getBytes(StandardCharsets.UTF_8));
        String tampered = original.replace("\"amount\":1000", "\"amount\":9000");

        sendWithSignature(eventId, now(), tampered, signature)
                .andExpect(status().isUnauthorized());
        // 공백 한 칸만 달라도 바이트가 다르므로 실패 (받은 바이트 그대로 검사)
        sendWithSignature(eventId, now(), original + " ", signature)
                .andExpect(status().isUnauthorized());
        sendWithSignature(eventId, now(), original, signature)
                .andExpect(status().isOk());
    }

    @Test
    void 타임스탬프가_5분_넘게_차이_나면_401이고_딱_5분은_통과() throws Exception {
        long nowSec = clock.instant().getEpochSecond();
        String e1 = newEventId();
        send(e1, String.valueOf(nowSec - 301), body(e1, 1), SECRET)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.result").value("TIMESTAMP_OUT_OF_RANGE"));
        String e2 = newEventId();
        send(e2, String.valueOf(nowSec + 301), body(e2, 1), SECRET)   // 미래도 막음
                .andExpect(status().isUnauthorized());
        String e3 = newEventId();
        send(e3, String.valueOf(nowSec - 300), body(e3, 1), SECRET)
                .andExpect(status().isOk());
        assertReceivedCount(1);
    }

    // ───────────── 중복 제거 ─────────────

    @Test
    void 같은_X_Webhook_Id가_두_번_오면_기록은_1개이고_중복_횟수는_1() throws Exception {
        String eventId = newEventId();
        send(eventId, now(), body(eventId, 5), SECRET).andExpect(jsonPath("$.result").value("RECEIVED"));
        send(eventId, now(), body(eventId, 5), SECRET)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("DUPLICATE_IGNORED"));

        mockMvc.perform(get("/mock-fintech/received"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].duplicateCount").value(1));
    }

    // ───────────── 그 밖의 거절 ─────────────

    @Test
    void 헤더와_본문의_eventId가_다르면_400() throws Exception {
        String eventId = newEventId();
        send(eventId, now(), body(newEventId(), 1), SECRET)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result").value("EVENT_ID_MISMATCH"));
    }

    // ───────────── 장애 흉내 ─────────────

    @Test
    void failRate가_1이면_항상_500이고_기록하지_않는다() throws Exception {
        faults("{\"failRate\":1.0}");
        String eventId = newEventId();
        send(eventId, now(), body(eventId, 1), SECRET)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.result").value("INJECTED_FAILURE"));
        assertReceivedCount(0);
    }

    @Test
    void failAfterProcessing이면_기록은_하고_500이며_다시_오면_중복으로_센다() throws Exception {
        faults("{\"failAfterProcessing\":true}");
        String eventId = newEventId();
        send(eventId, now(), body(eventId, 1), SECRET).andExpect(status().isInternalServerError());
        send(eventId, now(), body(eventId, 1), SECRET).andExpect(status().isInternalServerError());

        mockMvc.perform(get("/mock-fintech/received"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].duplicateCount").value(1));
    }

    @Test
    void delayMillis만큼_늦게_응답한다() throws Exception {
        faults("{\"delayMillis\":300}");
        String eventId = newEventId();
        long started = System.nanoTime();
        send(eventId, now(), body(eventId, 1), SECRET).andExpect(status().isOk());
        assertThat((System.nanoTime() - started) / 1_000_000).isGreaterThanOrEqualTo(300);
    }

    // ───────────── 관리 API ─────────────

    @Test
    void 장애_값이_범위를_벗어나면_400이고_빠진_칸은_그대로_둔다() throws Exception {
        faults("{\"failRate\":1.5}").andExpect(status().isBadRequest());
        faults("{\"delayMillis\":-1}").andExpect(status().isBadRequest());
        faults("{\"delayMillis\":120}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failRate").value(0.0))
                .andExpect(jsonPath("$.delayMillis").value(120))
                .andExpect(jsonPath("$.failAfterProcessing").value(false));
        mockMvc.perform(put("/mock-fintech/config").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 받은_목록은_DELETE로_비운다() throws Exception {
        String eventId = newEventId();
        send(eventId, now(), body(eventId, 1), SECRET).andExpect(status().isOk());
        mockMvc.perform(delete("/mock-fintech/received")).andExpect(status().isNoContent());
        assertReceivedCount(0);
    }

    @Test
    void 로그에_비밀키와_서명값이_없다(CapturedOutput output) throws Exception {
        String eventId = newEventId();
        String signature = sign(SECRET, now(), body(eventId, 1).getBytes(StandardCharsets.UTF_8));
        sendWithSignature(eventId, now(), body(eventId, 1), signature).andExpect(status().isOk());
        send(eventId, now(), body(eventId, 1), "whsec_wrong").andExpect(status().isUnauthorized());

        assertThat(output.getAll()).contains("모의 핀테크: 웹훅 비밀키 설정됨").contains("eventId=" + eventId)
                .doesNotContain(SECRET).doesNotContain(signature.substring(3));
    }

    // ───────────── 도우미 ─────────────

    private ResultActions send(String eventId, String timestamp, String body, String signWith) throws Exception {
        return sendWithSignature(eventId, timestamp, body, sign(signWith, timestamp, body.getBytes(StandardCharsets.UTF_8)));
    }

    private ResultActions sendWithSignature(String eventId, String timestamp, String body, String signature)
            throws Exception {
        return mockMvc.perform(post("/mock-fintech/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Webhook-Id", eventId)
                .header("X-Webhook-Timestamp", timestamp)
                .header("X-Webhook-Signature", signature)
                .content(body.getBytes(StandardCharsets.UTF_8)));
    }

    private ResultActions faults(String json) throws Exception {
        return mockMvc.perform(put("/mock-fintech/faults").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private void assertReceivedCount(int expected) throws Exception {
        mockMvc.perform(get("/mock-fintech/received")).andExpect(jsonPath("$", hasSize(expected)));
    }

    private String now() {
        return String.valueOf(clock.instant().getEpochSecond());
    }

    private static String newEventId() {
        return "evt_" + UUID.randomUUID();
    }

    // 보내는 쪽이 만드는 본문과 같은 모양
    private static String body(String eventId, long transferId) {
        return "{\"eventId\":\"" + eventId + "\",\"type\":\"transfer.completed\",\"createdAt\":\"2026-11-26T12:00:00+09:00\","
                + "\"data\":{\"transferId\":" + transferId + ",\"fromAccountNumber\":\"200-0001\","
                + "\"toAccountNumber\":\"200-0002\",\"amount\":1000,\"status\":\"COMPLETED\"}}";
    }

    private static String sign(String secret, String timestamp, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
        return "v1=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
}
