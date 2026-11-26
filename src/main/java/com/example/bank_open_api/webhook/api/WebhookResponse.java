package com.example.bank_open_api.webhook.api;

// [무엇] 웹훅 설정 응답 본문
// [역할] {url, secretPrefix, enabled} + 처음 등록 때만 secret(비밀키 원문, 이번 한 번만)
//        secret이 없을 때는 칸 자체를 빼고 보낸다(@JsonInclude NON_NULL) → "null"로라도 키 자리가 보이지 않게
// [누가 쓰나] WebhookController (PUT, GET)

import com.example.bank_open_api.webhook.domain.WebhookEndpoint;
import com.fasterxml.jackson.annotation.JsonInclude;

public record WebhookResponse(
        String url,
        String secretPrefix,   // 화면 표시용 앞 12글자 (예: whsec_AbC123)
        boolean enabled,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String secret          // 처음 등록 때만. 다시 볼 수 없으니 바로 안전한 곳에 저장해야 함
) {

    public static WebhookResponse of(WebhookEndpoint endpoint) {
        return new WebhookResponse(endpoint.getUrl(), endpoint.getSecretPrefix(), endpoint.isEnabled(), null);
    }

    public static WebhookResponse withSecret(WebhookEndpoint endpoint, String secret) {
        return new WebhookResponse(endpoint.getUrl(), endpoint.getSecretPrefix(), endpoint.isEnabled(), secret);
    }

    // 누가 이 객체를 로그에 찍어도 비밀키가 나가지 않게
    @Override
    public String toString() {
        return "WebhookResponse[url=" + url + ", secretPrefix=" + secretPrefix + ", enabled=" + enabled
                + ", secret=" + (secret == null ? "null" : "****") + "]";
    }
}
