package com.example.bank_open_api.mockfintech;

// [무엇] 모의 핀테크 서버 창구 (/mock-fintech/**)
// [역할] 우리 웹훅을 "받는 쪽"을 흉내 내는 실험용 API. mock-fintech.enabled=true일 때만 만들어진다
//          POST   /mock-fintech/webhooks   웹훅 받기 (서명·시각 검사, 중복 제거, 장애 흉내)
//          PUT    /mock-fintech/config     {secret}  개발자 포털에서 받은 웹훅 비밀키 넣기 (메모리, 로그 금지)
//          PUT    /mock-fintech/faults     {failRate, delayMillis, failAfterProcessing}
//          GET    /mock-fintech/received   받은 알림 목록
//          DELETE /mock-fintech/received   목록 초기화
// [누가 쓰나] 우리 서버의 웹훅 발송 일꾼(POST webhooks), 실험하는 사람(curl), 테스트
//            인증 없음 (MockFintechSecurityConfig). 로컬 실험 전용 — 운영에서는 enabled=false

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/mock-fintech")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mock-fintech.enabled", havingValue = "true")
public class MockFintechController {

    private final MockFintechReceiver receiver;

    // 비밀키 설정 요청. toString에서 값을 가림 (혹시 로그에 찍혀도 나가지 않게)
    public record ConfigRequest(String secret) {
        @Override
        public String toString() {
            return "ConfigRequest[secret=****]";
        }
    }

    public record FaultsRequest(Double failRate, Long delayMillis, Boolean failAfterProcessing) {
    }

    public record ReceivedResponse(String eventId, String type, Long transferId, LocalDateTime receivedAt,
                                   int duplicateCount) {
    }

    /**
     * 웹훅 받기. 본문은 byte[]로 받는다 → 서명 검사를 "받은 바이트 그대로" 하기 위해 (JSON으로 바꾸지 않음)
     * - 200 RECEIVED / DUPLICATE_IGNORED
     * - 400 본문 이상, 401 서명·시각 불일치, 500 장애 흉내, 503 비밀키 미설정
     */
    @PostMapping("/webhooks")
    public ResponseEntity<Map<String, String>> receive(
            @RequestHeader(value = "X-Webhook-Id", required = false) String eventId,
            @RequestHeader(value = "X-Webhook-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
            @RequestBody(required = false) byte[] body) {
        MockFintechReceiver.Outcome outcome =
                receiver.receive(eventId, timestamp, signature, body == null ? new byte[0] : body);
        return ResponseEntity.status(outcome.status()).body(Map.of("result", outcome.result()));
    }

    // 비밀키 넣기. 응답에 값을 되돌려 주지 않는다
    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> configure(@RequestBody ConfigRequest request) {
        if (request.secret() == null || request.secret().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("result", "SECRET_REQUIRED"));
        }
        receiver.configureSecret(request.secret().trim());
        return ResponseEntity.ok(Map.of("configured", true));
    }

    /**
     * 장애 설정. 빠진 칸은 지금 값 그대로
     * - failRate: 0~1, delayMillis: 0~60000
     */
    @PutMapping("/faults")
    public ResponseEntity<?> faults(@RequestBody FaultsRequest request) {
        MockFintechReceiver.Faults current = receiver.faults();
        double failRate = request.failRate() != null ? request.failRate() : current.failRate();
        long delayMillis = request.delayMillis() != null ? request.delayMillis() : current.delayMillis();
        boolean failAfter = request.failAfterProcessing() != null
                ? request.failAfterProcessing() : current.failAfterProcessing();
        if (!(failRate >= 0 && failRate <= 1)) {
            return ResponseEntity.badRequest().body(Map.of("result", "failRate는 0~1이어야 합니다"));
        }
        if (delayMillis < 0 || delayMillis > MockFintechReceiver.MAX_DELAY_MILLIS) {
            return ResponseEntity.badRequest().body(Map.of("result", "delayMillis는 0~60000이어야 합니다"));
        }
        return ResponseEntity.ok(receiver.updateFaults(new MockFintechReceiver.Faults(failRate, delayMillis, failAfter)));
    }

    @GetMapping("/received")
    public List<ReceivedResponse> received() {
        return receiver.received().stream()
                .map(e -> new ReceivedResponse(e.eventId(), e.type(), e.transferId(), e.receivedAt(), e.duplicateCount()))
                .toList();
    }

    @DeleteMapping("/received")
    public ResponseEntity<Void> clear() {
        receiver.clearReceived();
        return ResponseEntity.noContent().build();
    }
}
