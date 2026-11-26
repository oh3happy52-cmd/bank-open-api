package com.example.bank_open_api.webhook.api;

// [무엇] 비밀키 교체 응답 본문 {secret, secretPrefix}
// [역할] 새 비밀키 원문을 이번 한 번만 돌려준다
// [누가 쓰나] WebhookController (POST .../webhook/secret/rotate)

public record WebhookSecretResponse(String secret, String secretPrefix) {

    @Override
    public String toString() {
        return "WebhookSecretResponse[secret=****, secretPrefix=" + secretPrefix + "]";
    }
}
