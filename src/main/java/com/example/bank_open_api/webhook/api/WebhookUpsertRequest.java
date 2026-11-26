package com.example.bank_open_api.webhook.api;

// [무엇] 웹훅 등록·수정 요청 본문 {url}
// [역할] 빈 값만 여기서 막고(@NotBlank), 나머지 주소 규칙은 WebhookUrlValidator가 검사한다
// [누가 쓰나] WebhookController (PUT /api/v1/apps/{appId}/webhook)

import jakarta.validation.constraints.NotBlank;

public record WebhookUpsertRequest(
        @NotBlank(message = "웹훅 주소를 입력하세요")
        String url
) {
}
