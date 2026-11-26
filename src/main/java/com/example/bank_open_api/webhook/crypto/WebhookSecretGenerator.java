package com.example.bank_open_api.webhook.crypto;

// [무엇] 웹훅 서명 비밀키 생성기
// [역할] "whsec_" + SecureRandom 32바이트를 Base64URL(패딩 없음)로 바꾼 43글자 = 총 49글자 비밀키를 만든다
//        화면 표시용 앞 12글자(whsec_ + 6글자)도 함께 돌려준다
// [누가 쓰나] WebhookEndpointService (처음 등록, 비밀키 교체 때)

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

@Component
public class WebhookSecretGenerator {

    static final String PREFIX = "whsec_";   // "webhook secret" — 로그·코드에 실수로 들어갔을 때 눈에 띄게
    private static final int RANDOM_BYTES = 32;
    public static final int DISPLAY_PREFIX_LENGTH = 12;

    private final SecureRandom random = new SecureRandom();

    public record GeneratedSecret(String secret, String displayPrefix) {
        // 누가 실수로 이 객체를 로그에 찍어도 원문이 나가지 않게
        @Override
        public String toString() {
            return "GeneratedSecret[secret=****, displayPrefix=" + displayPrefix + "]";
        }
    }

    public GeneratedSecret generate() {
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        String secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new GeneratedSecret(secret, secret.substring(0, DISPLAY_PREFIX_LENGTH));
    }
}
