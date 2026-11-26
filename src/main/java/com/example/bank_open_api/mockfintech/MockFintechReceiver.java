package com.example.bank_open_api.mockfintech;

// [무엇] 모의 핀테크 서버의 "웹훅 받는 쪽" 로직 (메모리에만 저장)
// [역할] 실제 핀테크 회사가 우리 웹훅을 받을 때 해야 하는 일을 그대로 흉내 낸다
//        1) 서명 검사: v1=hex(HMAC-SHA256(비밀키, 타임스탬프 + "." + 받은 본문 바이트)) 와 헤더를 상수 시간 비교 → 틀리면 401
//        2) 시각 검사: 타임스탬프가 지금과 5분 넘게 차이 나면 401 (오래된 알림을 그대로 다시 보내는 공격 방지)
//        3) 중복 제거: 같은 X-Webhook-Id를 이미 받았으면 처리하지 않고 200 (중복 횟수만 +1)
//        4) 장애 흉내: delayMillis만큼 늦게 / failRate 확률로 500(기록 안 함) / failAfterProcessing이면 기록은 하고 500
// [누가 쓰나] MockFintechController
//
// ── 왜 "받은 바이트 그대로" 검사하나? ──
//   JSON으로 읽었다가 다시 문자열로 만들면 칸 순서·공백이 달라져 서명이 맞지 않는다.
//   보낸 쪽이 서명한 것은 "보낸 바이트"이므로 받는 쪽도 "받은 바이트"로 계산해야 한다.
//
// ── 왜 상수 시간 비교(MessageDigest.isEqual)인가? ──
//   보통의 equals는 앞에서부터 비교하다 다른 글자가 나오면 바로 멈춘다 → 응답 시간 차이로
//   "몇 글자째까지 맞았는지"를 알아내 서명을 한 글자씩 맞혀 가는 공격(타이밍 공격)이 가능하다.
//   isEqual은 길이가 같으면 항상 끝까지 비교해 걸리는 시간이 같다.
//
// ── 순서 ── 서명 → 시각 → (지연) → (failRate 500) → 중복 확인·기록 → (failAfterProcessing 500)
//   서명·시각 검사를 먼저 하는 이유: 검증 안 된 요청으로는 아무 상태도 바꾸지 않기 위해
//
// ── 로그 ── 비밀키·서명값·본문은 남기지 않는다. eventId와 결과만

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@ConditionalOnProperty(name = "mock-fintech.enabled", havingValue = "true")
public class MockFintechReceiver {

    // 타임스탬프 허용 오차 (지금 ± 5분)
    static final Duration TOLERANCE = Duration.ofMinutes(5);
    // 응답 지연 최대값 (실수로 아주 큰 값을 넣어 요청 스레드가 오래 묶이지 않게)
    static final long MAX_DELAY_MILLIS = 60_000;

    private final JsonMapper jsonMapper;
    private final Clock clock;

    // 개발자 포털에서 받은 웹훅 비밀키 (메모리에만. 서버를 끄면 사라짐)
    private final AtomicReference<String> secret = new AtomicReference<>();
    private volatile Faults faults = Faults.NONE;

    // eventId → 받은 알림. 받은 순서를 보여 주려고 번호를 붙인다
    private final Map<String, ReceivedEvent> received = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public MockFintechReceiver(JsonMapper jsonMapper, Clock clock) {
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    // 장애 설정
    public record Faults(double failRate, long delayMillis, boolean failAfterProcessing) {
        static final Faults NONE = new Faults(0, 0, false);
    }

    // 받은 알림 1건 (중복 횟수는 늘어날 수 있음)
    public static final class ReceivedEvent {
        private final long sequence;
        private final String eventId;
        private final String type;
        private final Long transferId;
        private final LocalDateTime receivedAt;
        private final AtomicInteger duplicateCount = new AtomicInteger();

        ReceivedEvent(long sequence, String eventId, String type, Long transferId, LocalDateTime receivedAt) {
            this.sequence = sequence;
            this.eventId = eventId;
            this.type = type;
            this.transferId = transferId;
            this.receivedAt = receivedAt;
        }

        public String eventId() { return eventId; }
        public String type() { return type; }
        public Long transferId() { return transferId; }
        public LocalDateTime receivedAt() { return receivedAt; }
        public int duplicateCount() { return duplicateCount.get(); }
    }

    // 처리 결과: HTTP 상태 + 짧은 결과 이름 (응답 본문에 씀)
    public record Outcome(int status, String result) {
    }

    // ───────────── 관리 ─────────────

    public void configureSecret(String newSecret) {
        secret.set(newSecret);
        log.info("모의 핀테크: 웹훅 비밀키 설정됨");   // 값은 남기지 않음
    }

    public boolean hasSecret() {
        return secret.get() != null;
    }

    public Faults updateFaults(Faults newFaults) {
        this.faults = newFaults;
        log.info("모의 핀테크: 장애 설정 failRate={}, delayMillis={}, failAfterProcessing={}",
                newFaults.failRate(), newFaults.delayMillis(), newFaults.failAfterProcessing());
        return newFaults;
    }

    public Faults faults() {
        return faults;
    }

    // 받은 순서대로
    public List<ReceivedEvent> received() {
        return received.values().stream().sorted(Comparator.comparingLong(e -> e.sequence)).toList();
    }

    public void clearReceived() {
        received.clear();
    }

    // ───────────── 웹훅 받기 ─────────────

    public Outcome receive(String eventId, String timestampHeader, String signatureHeader, byte[] body) {
        String currentSecret = secret.get();
        if (currentSecret == null) {
            // 아직 비밀키를 안 넣음 → 검증할 수 없다. 보내는 쪽이 나중에 다시 보내도록 503
            log.warn("모의 핀테크: 비밀키가 없어 웹훅을 거절함 eventId={}", eventId);
            return new Outcome(503, "SECRET_NOT_CONFIGURED");
        }

        // 1) 서명 (타임스탬프 헤더 문자열 그대로 + "." + 본문 바이트)
        if (timestampHeader == null || signatureHeader == null
                || !signatureMatches(currentSecret, timestampHeader, body, signatureHeader)) {
            log.warn("모의 핀테크: 서명 불일치 eventId={}", eventId);
            return new Outcome(401, "INVALID_SIGNATURE");
        }

        // 2) 시각
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader);
        } catch (NumberFormatException e) {
            return new Outcome(401, "INVALID_TIMESTAMP");
        }
        Duration skew = Duration.between(Instant.ofEpochSecond(timestamp), clock.instant()).abs();
        if (skew.compareTo(TOLERANCE) > 0) {
            log.warn("모의 핀테크: 타임스탬프가 5분 넘게 차이 남 eventId={}, skewSeconds={}", eventId, skew.toSeconds());
            return new Outcome(401, "TIMESTAMP_OUT_OF_RANGE");
        }

        // 서명이 맞아도 본문이 우리가 아는 모양인지, 헤더 eventId와 본문 eventId가 같은지 확인
        JsonNode json;
        try {
            json = jsonMapper.readTree(body);
        } catch (RuntimeException e) {
            return new Outcome(400, "INVALID_BODY");
        }
        if (eventId == null || !eventId.equals(json.path("eventId").asString(null))) {
            return new Outcome(400, "EVENT_ID_MISMATCH");
        }

        // 4-a) 장애: 지연, 확률 실패(처리 전)
        Faults current = faults;
        sleep(current.delayMillis());
        if (current.failRate() > 0 && ThreadLocalRandom.current().nextDouble() < current.failRate()) {
            log.info("모의 핀테크: 일부러 500 (처리 전) eventId={}", eventId);
            return new Outcome(500, "INJECTED_FAILURE");
        }

        // 3) 중복 제거 + 기록
        ReceivedEvent created = new ReceivedEvent(sequence.incrementAndGet(), eventId,
                json.path("type").asString(null),
                json.path("data").path("transferId").isNumber() ? json.path("data").path("transferId").asLong() : null,
                LocalDateTime.now(clock));
        ReceivedEvent existing = received.putIfAbsent(eventId, created);
        boolean duplicate = existing != null;
        if (duplicate) {
            existing.duplicateCount.incrementAndGet();
        }

        // 4-b) 장애: 기록은 했지만 500 (보내는 쪽은 실패로 보고 다시 보냄 → 다음엔 중복으로 걸러짐)
        if (current.failAfterProcessing()) {
            log.info("모의 핀테크: 기록 후 일부러 500 eventId={}, duplicate={}", eventId, duplicate);
            return new Outcome(500, "INJECTED_FAILURE_AFTER_PROCESSING");
        }

        log.info("모의 핀테크: 웹훅 받음 eventId={}, duplicate={}", eventId, duplicate);
        return new Outcome(200, duplicate ? "DUPLICATE_IGNORED" : "RECEIVED");
    }

    // 상수 시간 비교 (MessageDigest.isEqual)
    private static boolean signatureMatches(String secret, String timestamp, byte[] body, String signatureHeader) {
        String expected = sign(secret, timestamp, body);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.trim().getBytes(StandardCharsets.UTF_8));
    }

    // 보내는 쪽(WebhookSigner)과 같은 규칙이지만, 받는 쪽 입장에서 따로 구현했다 (서로의 실수를 잡아내도록)
    private static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            return "v1=" + HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256을 쓸 수 없습니다", e);
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
