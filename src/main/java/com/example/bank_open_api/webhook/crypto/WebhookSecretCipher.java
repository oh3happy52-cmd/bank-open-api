package com.example.bank_open_api.webhook.crypto;

// [무엇] 웹훅 서명 비밀키 암호화·복호화 도구 (AES-256-GCM)
// [역할] 비밀키를 DB에 넣기 전에 암호화하고, 서명할 때 다시 꺼내 복호화한다
// [누가 쓰나] WebhookEndpointService(저장할 때 암호화), WebhookDispatcher(보낼 때 복호화)
//
// ── 왜 API 키처럼 해시하지 않고 암호화하나? ──
//   API 키는 "맞는지 비교"만 하면 돼서 해시로 충분했다. 웹훅 비밀키는 보낼 때마다 그 값으로 서명해야 하므로
//   원래 값을 다시 꺼낼 수 있어야 한다 → 되돌릴 수 있는 암호화가 필요하다.
//
// ── AES-256-GCM ──
//   - 키: .env의 WEBHOOK_ENCRYPTION_KEY (Base64로 적은 32바이트 = 256비트). DB와 따로 보관 → DB만 새어 나가면 풀 수 없음
//   - IV(초기값) 12바이트를 암호화할 때마다 새로 무작위로 만든다 → 같은 비밀키도 암호문이 매번 다르다
//     (GCM은 같은 키로 같은 IV를 두 번 쓰면 안전성이 깨진다)
//   - 인증 태그(16바이트): 암호문이 한 글자라도 바뀌면 복호화가 실패한다 (위조·손상 감지)
//   - AAD(추가 인증 데이터)로 앱 번호를 묶는다: A앱의 암호문을 B앱 줄에 복사해 넣으면 복호화가 실패한다
//   - 저장 모양: "v1:" + Base64(IV + 암호문 + 태그). "v1"은 나중에 암호화 키를 바꿀 때 어느 키로 풀지 구분하는 표시
//
// ── 서버 시작 검사 ──
//   키가 없거나, Base64가 아니거나, 32바이트가 아니면 이 빈을 만들 때 예외 → 서버가 시작되지 않는다

import com.example.bank_open_api.webhook.config.WebhookProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class WebhookSecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;        // AES-256
    private static final int IV_BYTES = 12;         // GCM 권장 IV 길이
    private static final int TAG_BITS = 128;        // 인증 태그 16바이트
    private static final String VERSION_PREFIX = "v1:";

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public WebhookSecretCipher(WebhookProperties properties) {
        this.key = parseKey(properties.encryptionKey());
    }

    private static SecretKey parseKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "WEBHOOK_ENCRYPTION_KEY가 없습니다. .env에 'openssl rand -base64 32'로 만든 값을 넣으세요");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("WEBHOOK_ENCRYPTION_KEY가 Base64 형식이 아닙니다");
        }
        if (raw.length != KEY_BYTES) {
            // 값 자체는 메시지에 넣지 않는다. 길이만 알려 준다
            throw new IllegalStateException("WEBHOOK_ENCRYPTION_KEY는 32바이트여야 합니다 (지금 " + raw.length + "바이트)");
        }
        return new SecretKeySpec(raw, "AES");
    }

    /**
     * @param secret 비밀키 원문 (whsec_...)
     * @param appId  이 비밀키의 앱 번호 (AAD로 묶음)
     * @return "v1:" + Base64(IV + 암호문 + 태그)
     */
    public String encrypt(String secret, Long appId) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);   // 매번 새 IV
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(appId));
            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
            return VERSION_PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("웹훅 비밀키 암호화 실패", e);
        }
    }

    /**
     * @throws IllegalStateException 암호문이 손상됐거나, 다른 키·다른 앱으로 만든 암호문일 때
     */
    public String decrypt(String stored, Long appId) {
        if (stored == null || !stored.startsWith(VERSION_PREFIX)) {
            throw new IllegalStateException("알 수 없는 웹훅 비밀키 암호문 형식");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(VERSION_PREFIX.length()));
            if (all.length <= IV_BYTES) {
                throw new IllegalStateException("웹훅 비밀키 암호문이 너무 짧습니다");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            cipher.updateAAD(aad(appId));
            byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // 원인 메시지에도 비밀값은 없지만, 로그에는 예외 종류만 남기도록 부르는 쪽에서 처리한다
            throw new IllegalStateException("웹훅 비밀키 복호화 실패", e);
        }
    }

    private static byte[] aad(Long appId) {
        return ("webhook-secret:app-" + appId).getBytes(StandardCharsets.UTF_8);
    }
}
