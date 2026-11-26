package com.example.bank_open_api.webhook.api;

// [무엇] 웹훅 전송 기록 한 줄 응답
// [역할] {eventId, type, transferId, status, attemptCount, lastStatusCode, nextAttemptAt, createdAt, deliveredAt}
//        payload(계좌번호 전체 포함)와 last_error는 내보내지 않는다
//        nextAttemptAt은 PENDING일 때만 의미가 있어서 그 밖의 상태에서는 null
// [누가 쓰나] WebhookController (GET .../webhook/deliveries)

import com.example.bank_open_api.webhook.domain.DeliveryStatus;
import com.example.bank_open_api.webhook.domain.WebhookDelivery;

import java.time.LocalDateTime;

public record WebhookDeliveryResponse(
        String eventId,
        String type,
        Long transferId,
        DeliveryStatus status,
        int attemptCount,
        Integer lastStatusCode,
        LocalDateTime nextAttemptAt,
        LocalDateTime createdAt,
        LocalDateTime deliveredAt
) {

    public static WebhookDeliveryResponse from(WebhookDelivery d) {
        return new WebhookDeliveryResponse(
                d.getEventId(), d.getEventType(), d.getTransferId(), d.getStatus(), d.getAttemptCount(),
                d.getLastStatusCode() == null ? null : d.getLastStatusCode().intValue(),
                d.getStatus() == DeliveryStatus.PENDING ? d.getNextAttemptAt() : null,
                d.getCreatedAt(), d.getDeliveredAt());
    }
}
