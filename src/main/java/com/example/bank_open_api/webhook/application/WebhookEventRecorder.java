package com.example.bank_open_api.webhook.application;

// [무엇] 웹훅 알림 저장기 (아웃박스에 1줄 적기)
// [역할] 이체가 성공하면, 그 앱의 웹훅이 켜져 있을 때 webhook_deliveries에 PENDING 1줄을 저장한다
//        여기서는 보내지 않는다. 보내기는 발송 일꾼(WebhookDispatcher)이 따로 한다
//        payload는 지금 확정한다(스냅샷): {eventId, type, createdAt, data:{transferId, from, to, amount, status}}
// [누가 쓰나] TransferSuccessHandler (이체 성공은 모두 그곳을 지난다)
//
// ── 같은 Idempotency-Key 재요청 ──
//   bank_core가 처음 결과(같은 transferId)를 다시 주므로 여기도 다시 온다.
//   UNIQUE(app_id, transfer_id, event_type) 때문에 두 번째 저장은 막힌다 → 조용히 무시 (알림 1줄만)
//
// ── 저장이 실패하면? ──
//   이 메서드는 절대 예외를 밖으로 던지지 않는다. ERROR 로그만 남기고, 이체 응답(201)은 그대로 나간다.
//   (돈은 이미 bank_core에서 옮겨졌으므로 알림 저장 실패 때문에 이체를 실패로 보이게 하면 안 된다)
//   앱이 같은 키로 다시 요청하면 그때 다시 저장을 시도한다.
//
// ── 왜 "보내지 않고 저장만"인가? (아웃박스) ──
//   응답하면서 바로 보내면: 받는 쪽이 느리면 이체 응답도 느려지고, 실패하면 다시 보낼 근거가 남지 않는다.
//   DB에 먼저 적어 두면: 받는 쪽이 죽어 있어도, 우리 서버가 재시작돼도 적힌 줄을 보고 이어서 보낼 수 있다.

import com.example.bank_open_api.transfer.application.TransferResult;
import com.example.bank_open_api.webhook.domain.WebhookDelivery;
import com.example.bank_open_api.webhook.domain.WebhookDeliveryRepository;
import com.example.bank_open_api.webhook.domain.WebhookEndpoint;
import com.example.bank_open_api.webhook.domain.WebhookEndpointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookEventRecorder {

    public static final String TRANSFER_COMPLETED = "transfer.completed";
    static final String DUPLICATE_CONSTRAINT = "uk_webhook_deliveries_app_transfer_type";

    private final WebhookEndpointRepository endpointRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public void recordTransferCompleted(Long appId, TransferResult result) {
        try {
            Optional<WebhookEndpoint> endpoint = endpointRepository.findByAppId(appId);
            if (endpoint.isEmpty() || !endpoint.get().isEnabled()) {
                return;   // 웹훅을 안 쓰는 앱이거나 꺼 둔 앱
            }
            if (deliveryRepository.existsByAppIdAndTransferIdAndEventType(appId, result.transferId(), TRANSFER_COMPLETED)) {
                log.debug("웹훅 알림 이미 있음(같은 이체 재요청): appId={}, transferId={}", appId, result.transferId());
                return;
            }

            String eventId = "evt_" + UUID.randomUUID();
            OffsetDateTime createdAt = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
            String payload = jsonMapper.writeValueAsString(payload(eventId, createdAt, result));

            // saveAndFlush: INSERT를 바로 보내 UNIQUE 충돌을 여기서 알아챈다 (동시에 같은 이체 재요청이 2개 온 경우)
            deliveryRepository.saveAndFlush(WebhookDelivery.pending(
                    appId, eventId, TRANSFER_COMPLETED, result.transferId(), payload, LocalDateTime.now(clock)));
            log.info("웹훅 알림 저장: appId={}, eventId={}, transferId={}", appId, eventId, result.transferId());

        } catch (DataIntegrityViolationException e) {
            if (isDuplicate(e)) {
                log.debug("웹훅 알림 이미 있음(동시 재요청): appId={}, transferId={}", appId, result.transferId());
                return;
            }
            log.error("웹훅 알림 저장 실패(이체 응답은 정상): appId={}, transferId={}, error={}",
                    appId, result.transferId(), e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            log.error("웹훅 알림 저장 실패(이체 응답은 정상): appId={}, transferId={}, error={}",
                    appId, result.transferId(), e.getClass().getSimpleName());
        }
    }

    // 칸 순서를 정해 두려고 LinkedHashMap (MySQL JSON 열은 저장할 때 순서를 다시 정리하지만, 내용은 같다)
    private static Map<String, Object> payload(String eventId, OffsetDateTime createdAt, TransferResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("transferId", result.transferId());
        data.put("fromAccountNumber", result.fromAccountNumber());
        data.put("toAccountNumber", result.toAccountNumber());
        data.put("amount", result.amount());
        data.put("status", result.status());

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId);
        event.put("type", TRANSFER_COMPLETED);
        event.put("createdAt", createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));   // 예: 2026-11-27T10:00:00+09:00
        event.put("data", data);
        return event;
    }

    private static boolean isDuplicate(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(DUPLICATE_CONSTRAINT)) {
                return true;
            }
        }
        return false;
    }
}
