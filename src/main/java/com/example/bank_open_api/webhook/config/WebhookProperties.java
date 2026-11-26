package com.example.bank_open_api.webhook.config;

// [무엇] 웹훅 설정값 묶음
// [역할] application.properties의 webhook.* 값을 한 객체로 받는다
//          webhook.encryption-key        비밀키 암호화용 AES-256 키 (Base64 32바이트, .env의 WEBHOOK_ENCRYPTION_KEY)
//          webhook.allow-http            true면 http:// 주소도 허용 (로컬 개발용), false면 https만
//          webhook.http.*                받는 쪽 서버 호출 제한 시간 (연결 2초, 응답 3초)
//          webhook.retry.*               실패 시 다시 보내는 간격(2초 × 2^(n-1))과 최대 시도 횟수(6)
//          webhook.dispatch.*            발송 일꾼: 켜짐 여부, 실행 간격(1초), 한 번에 꺼낼 개수(20), 맡아 두는 시간(lease)
//        값이 잘못되면 서버가 시작할 때 바로 멈춰서 알려 준다
// [누가 쓰나] WebhookSecretCipher, WebhookUrlValidator, WebhookConfig, WebhookDeliveryStore, WebhookDispatchScheduler

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "webhook")
public record WebhookProperties(
        String encryptionKey,
        boolean allowHttp,
        Http http,
        Retry retry,
        Dispatch dispatch
) {

    public record Http(Duration connectTimeout, Duration readTimeout) {
    }

    public record Retry(Duration base, int maxAttempts) {
        public Retry {
            if (base == null || base.isNegative() || base.isZero()) {
                throw new IllegalArgumentException("webhook.retry.base는 0보다 커야 합니다");
            }
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("webhook.retry.max-attempts는 1 이상이어야 합니다");
            }
        }
    }

    /**
     * @param lease 일꾼이 알림을 꺼낸 뒤 "내가 보내는 중"으로 맡아 두는 시간.
     *              이 시간 동안은 다른 일꾼이 같은 알림을 꺼내지 않는다. 한 번에 꺼낸 개수를 모두 보내는 최대 시간보다 길어야 한다
     *              (20개 × (연결 2초 + 응답 3초) = 100초 → 기본 2분)
     */
    public record Dispatch(boolean enabled, Duration interval, int batchSize, Duration lease) {
        public Dispatch {
            if (batchSize < 1) {
                throw new IllegalArgumentException("webhook.dispatch.batch-size는 1 이상이어야 합니다");
            }
        }
    }

    // record의 기본 toString()은 모든 칸을 찍는다 → 누가 이 객체를 로그에 찍어도 암호화 키가 나가지 않게 가림
    @Override
    public String toString() {
        return "WebhookProperties[encryptionKey=****, allowHttp=" + allowHttp + ", http=" + http
                + ", retry=" + retry + ", dispatch=" + dispatch + "]";
    }
}
