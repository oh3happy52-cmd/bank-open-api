package com.example.bank_open_api.webhook.crypto;

// [무엇] 웹훅 비밀키 암호화 단위 테스트 (AES-256-GCM)
// [역할] - 암호화 → 복호화하면 원래 값
//        - 같은 비밀키를 두 번 암호화해도 암호문이 매번 다름 (매번 새 IV)
//        - 암호문에 원문이 보이지 않음
//        - 다른 앱 번호로 풀거나(AAD), 암호문을 한 글자 바꾸거나, 다른 키로 풀면 실패
//        - 키가 없거나 Base64가 아니거나 32바이트가 아니면 만들 때 실패 (= 서버 시작 실패)
// [누가 쓰나] ./gradlew test (Docker 필요 없음)

import com.example.bank_open_api.webhook.config.WebhookProperties;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookSecretCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    private static final String OTHER_KEY = Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes());

    private final WebhookSecretCipher cipher = new WebhookSecretCipher(props(KEY));
    private final String secret = new WebhookSecretGenerator().generate().secret();

    @Test
    void 암호화한_것을_복호화하면_원래_비밀키이고_암호문에_원문이_없다() {
        String stored = cipher.encrypt(secret, 1L);

        assertThat(stored).startsWith("v1:");
        assertThat(stored).doesNotContain(secret).doesNotContain(secret.substring(6));
        assertThat(cipher.decrypt(stored, 1L)).isEqualTo(secret);
    }

    @Test
    void 같은_비밀키도_암호화할_때마다_암호문이_다르다() {
        String first = cipher.encrypt(secret, 1L);
        String second = cipher.encrypt(secret, 1L);

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first, 1L)).isEqualTo(cipher.decrypt(second, 1L));
    }

    @Test
    void 다른_앱_번호_손상된_암호문_다른_키로는_풀_수_없다() {
        String stored = cipher.encrypt(secret, 1L);

        assertThatThrownBy(() -> cipher.decrypt(stored, 2L)).isInstanceOf(IllegalStateException.class);

        char[] chars = stored.toCharArray();
        int last = chars.length - 3;
        chars[last] = chars[last] == 'A' ? 'B' : 'A';
        assertThatThrownBy(() -> cipher.decrypt(new String(chars), 1L)).isInstanceOf(IllegalStateException.class);

        WebhookSecretCipher other = new WebhookSecretCipher(props(OTHER_KEY));
        assertThatThrownBy(() -> other.decrypt(stored, 1L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 키가_없거나_Base64가_아니거나_32바이트가_아니면_만들_때_실패한다() {
        assertThatThrownBy(() -> new WebhookSecretCipher(props("")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("WEBHOOK_ENCRYPTION_KEY가 없습니다");
        assertThatThrownBy(() -> new WebhookSecretCipher(props("!!!not-base64!!!")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Base64");
        String shortKey = Base64.getEncoder().encodeToString("only-16-bytes!!!".getBytes());
        assertThatThrownBy(() -> new WebhookSecretCipher(props(shortKey)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32바이트")
                .hasMessageNotContaining(shortKey);   // 키 값 자체는 메시지에 넣지 않음
    }

    @Test
    void 비밀키_모양은_whsec_와_43글자이고_앞_12글자를_표시용으로_준다() {
        var generated = new WebhookSecretGenerator().generate();
        assertThat(generated.secret()).startsWith("whsec_").hasSize(49).matches("whsec_[A-Za-z0-9_-]{43}");
        assertThat(generated.displayPrefix()).isEqualTo(generated.secret().substring(0, 12));
        assertThat(generated.toString()).doesNotContain(generated.secret());
    }

    private static WebhookProperties props(String key) {
        return new WebhookProperties(key, true, null, null, null);
    }
}
