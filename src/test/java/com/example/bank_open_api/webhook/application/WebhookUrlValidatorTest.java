package com.example.bank_open_api.webhook.application;

// [무엇] 웹훅 주소 검사 단위 테스트
// [역할] http/https만, 2048자 이하, user:pass@ 금지, 서버 이름 필요, allow-http=false면 https만
// [누가 쓰나] ./gradlew test (Docker 필요 없음)

import com.example.bank_open_api.common.exception.BusinessException;
import com.example.bank_open_api.common.exception.ErrorCode;
import com.example.bank_open_api.webhook.config.WebhookProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookUrlValidatorTest {

    private final WebhookUrlValidator allowHttp = new WebhookUrlValidator(props(true));
    private final WebhookUrlValidator httpsOnly = new WebhookUrlValidator(props(false));

    @Test
    void 올바른_http_https_주소는_통과하고_앞뒤_공백은_뗀다() {
        assertThat(allowHttp.validate("https://fintech.example.com/webhooks")).isEqualTo("https://fintech.example.com/webhooks");
        assertThat(allowHttp.validate("  http://127.0.0.1:9000/hook?x=1  ")).isEqualTo("http://127.0.0.1:9000/hook?x=1");
        assertThat(httpsOnly.validate("HTTPS://fintech.example.com/w")).isEqualTo("HTTPS://fintech.example.com/w");
    }

    @Test
    void http_https가_아니면_400() {
        assertInvalid(allowHttp, "ftp://example.com/x");
        assertInvalid(allowHttp, "javascript:alert(1)");
        assertInvalid(allowHttp, "file:///etc/passwd");
        assertInvalid(allowHttp, "example.com/webhook");   // scheme 없음
    }

    @Test
    void allow_http가_false면_http는_400() {
        assertInvalid(httpsOnly, "http://fintech.example.com/webhooks");
    }

    @Test
    void 아이디_비밀번호가_들어간_주소는_400() {
        assertInvalid(allowHttp, "https://user:pass@fintech.example.com/webhooks");
        assertInvalid(allowHttp, "https://user@fintech.example.com/webhooks");
    }

    @Test
    void 길이_서버이름_형식이_틀리면_400() {
        String base = "https://fintech.example.com/";
        assertThat(allowHttp.validate(base + "a".repeat(2048 - base.length()))).hasSize(2048);
        assertInvalid(allowHttp, base + "a".repeat(2049 - base.length()));
        assertInvalid(allowHttp, "https:///no-host");
        assertInvalid(allowHttp, "https://exa mple.com/");
        assertInvalid(allowHttp, " ");
    }

    private static void assertInvalid(WebhookUrlValidator validator, String url) {
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_WEBHOOK_URL);
    }

    private static WebhookProperties props(boolean allowHttp) {
        return new WebhookProperties("unused", allowHttp, null, null, null);
    }
}
