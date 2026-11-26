package com.example.bank_open_api.webhook.application;

// [무엇] 발송 일꾼이 꺼내 온 알림 1건 (DB 트랜잭션 밖으로 들고 나가는 값)
// [역할] 엔티티 대신 보내는 데 필요한 값만 담은 불변 record. 트랜잭션이 끝난 뒤 HTTP를 보내는 동안 쓴다
// [누가 쓰나] WebhookDeliveryStore(만듦), WebhookDispatcher(보냄)

import com.example.bank_open_api.webhook.domain.WebhookDelivery;

public record ClaimedDelivery(
        Long id,
        Long appId,
        String eventId,
        String payload,     // DB에서 읽은 문자열 그대로 → 이 바이트를 서명하고 이 바이트를 보낸다
        int attemptCount    // 이번 시도 전까지 보낸 횟수
) {

    static ClaimedDelivery from(WebhookDelivery delivery) {
        return new ClaimedDelivery(delivery.getId(), delivery.getAppId(), delivery.getEventId(),
                delivery.getPayload(), delivery.getAttemptCount());
    }

    // payload에는 계좌번호 전체가 들어 있다 → 실수로 로그에 찍혀도 나가지 않게 가림
    @Override
    public String toString() {
        return "ClaimedDelivery[id=" + id + ", appId=" + appId + ", eventId=" + eventId
                + ", payload=****, attemptCount=" + attemptCount + "]";
    }
}
