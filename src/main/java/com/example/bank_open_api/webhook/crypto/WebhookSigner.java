package com.example.bank_open_api.webhook.crypto;

// [무엇] 웹훅 서명 도구 (HMAC-SHA256)
// [역할] X-Webhook-Signature 헤더 값 "v1={16진수 서명}" 을 만든다
//          서명할 내용 = 타임스탬프(유닉스 초, 문자열) + "." + 본문 바이트
//          키          = 비밀키 원문 문자열(whsec_...)의 UTF-8 바이트
//        받는 쪽은 같은 비밀키로 같은 계산을 해서 헤더 값과 비교한다 → 우리가 보냈는지, 본문이 바뀌지 않았는지 확인
//        타임스탬프를 서명에 넣는 이유: 오래된 알림을 그대로 다시 보내는 공격(재전송 공격)을 받는 쪽이 시각으로 거를 수 있게
// [누가 쓰나] WebhookDispatcher. (다음 단계의 모의 핀테크 서버도 같은 규칙으로 검증)
//
// 서명값·비밀키는 로그·DB에 남기지 않는다.

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

public final class WebhookSigner {

    public static final String VERSION = "v1";
    private static final String ALGORITHM = "HmacSHA256";

    private WebhookSigner() {
    }

    /**
     * @param secret    비밀키 원문 (whsec_...)
     * @param timestamp 유닉스 초 (X-Webhook-Timestamp 헤더와 같은 값)
     * @param body      실제로 보낼 본문 바이트 (이 바이트를 그대로 보내야 서명이 맞는다)
     * @return "v1=" + 16진수 소문자 64글자
     */
    public static String sign(String secret, long timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            return VERSION + "=" + HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256을 쓸 수 없습니다", e);
        }
    }
}
