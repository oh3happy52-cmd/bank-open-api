package com.example.bank_open_api.webhook.api;

// [무엇] 웹훅 전송 기록 목록 응답 (페이지)
// [역할] {items, page, size, totalElements, totalPages}
//        Spring의 Page 객체를 그대로 JSON으로 내보내면 모양이 버전마다 달라질 수 있어 우리 모양으로 고정
// [누가 쓰나] WebhookController

import com.example.bank_open_api.webhook.domain.WebhookDelivery;
import org.springframework.data.domain.Page;

import java.util.List;

public record WebhookDeliveryPageResponse(
        List<WebhookDeliveryResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static WebhookDeliveryPageResponse from(Page<WebhookDelivery> page) {
        return new WebhookDeliveryPageResponse(
                page.getContent().stream().map(WebhookDeliveryResponse::from).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
