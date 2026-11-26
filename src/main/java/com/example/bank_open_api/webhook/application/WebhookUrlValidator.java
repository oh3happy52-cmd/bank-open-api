package com.example.bank_open_api.webhook.application;

// [무엇] 웹훅 주소 검사기
// [역할] 등록하려는 url이 규칙에 맞는지 본다. 어기면 400 INVALID_WEBHOOK_URL
//          - http 또는 https만 (webhook.allow-http=false면 https만)
//          - 2048자 이하
//          - 주소에 아이디·비밀번호(user:pass@) 금지 → 비밀값이 DB·화면에 평문으로 남지 않게
//          - 호스트(서버 이름)가 있어야 함
// [누가 쓰나] WebhookEndpointService (PUT)
//
// 오류 메시지에는 고정 문구만 넣는다 (입력한 주소를 그대로 되돌려 주지 않음)

import com.example.bank_open_api.common.exception.BusinessException;
import com.example.bank_open_api.common.exception.ErrorCode;
import com.example.bank_open_api.webhook.config.WebhookProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

@Component
public class WebhookUrlValidator {

    static final int MAX_LENGTH = 2048;

    private final boolean allowHttp;

    public WebhookUrlValidator(WebhookProperties properties) {
        this.allowHttp = properties.allowHttp();
    }

    /**
     * @return 앞뒤 공백을 뗀 주소
     */
    public String validate(String url) {
        if (url == null || url.isBlank()) {
            throw invalid("주소를 입력하세요");
        }
        String trimmed = url.trim();
        if (trimmed.length() > MAX_LENGTH) {
            throw invalid("주소는 2048자 이하여야 합니다");
        }

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw invalid("주소 형식이 올바르지 않습니다");
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw invalid("http 또는 https 주소만 사용할 수 있습니다");
        }
        if (scheme.equals("http") && !allowHttp) {
            throw invalid("https 주소만 사용할 수 있습니다");
        }
        if (uri.getRawUserInfo() != null) {
            throw invalid("주소에 아이디·비밀번호를 넣을 수 없습니다");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw invalid("주소에 서버 이름이 없습니다");
        }
        return trimmed;
    }

    private static BusinessException invalid(String reason) {
        return new BusinessException(ErrorCode.INVALID_WEBHOOK_URL, reason);
    }
}
