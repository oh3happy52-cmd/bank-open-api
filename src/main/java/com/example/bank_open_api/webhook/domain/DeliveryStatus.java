package com.example.bank_open_api.webhook.domain;

// [무엇] 웹훅 전송 상태
// [역할] PENDING(보낼 차례 기다림) → SUCCEEDED(2xx 받음) / FAILED(최대 횟수 실패) / CANCELED(보낼 때 웹훅이 꺼져 있음)
//        실패해도 횟수가 남았으면 PENDING으로 남고 다음 시각(next_attempt_at)만 뒤로 밀린다
// [누가 쓰나] WebhookDelivery, WebhookDeliveryStore

public enum DeliveryStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    CANCELED
}
