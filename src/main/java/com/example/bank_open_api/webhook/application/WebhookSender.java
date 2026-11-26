package com.example.bank_open_api.webhook.application;

// [무엇] 웹훅 HTTP 전송기 (받는 쪽 서버에 POST 1번)
// [역할] 서명까지 끝난 본문 바이트를 그대로 보내고, 결과를 SendOutcome으로 돌려준다 (예외를 던지지 않음)
//          2xx                        → 성공
//          그 밖의 상태(3xx·4xx·5xx)   → 실패 "HTTP 500"
//          응답 제한 시간 초과          → 실패 "TIMEOUT"
//          연결 실패(꺼짐·주소 못 찾음) → 실패 "CONNECTION_FAILED"
//        받는 쪽의 응답 본문은 읽지도 저장하지도 않는다 (크기를 알 수 없고, 무엇이 들어 있을지 모름)
// [누가 쓰나] WebhookDispatcher
//
// 헤더
//   Content-Type: application/json
//   X-Webhook-Id: {eventId}               같은 알림의 재전송이면 같은 값 → 받는 쪽이 중복을 거른다
//   X-Webhook-Timestamp: {유닉스 초}
//   X-Webhook-Signature: v1={hex}         WebhookSigner 참고
//
// 로그: 이 클래스는 로그를 남기지 않는다 (주소·서명·본문이 섞일 수 있어서). 결과 요약은 WebhookDispatcher가 남긴다

import com.example.bank_open_api.webhook.config.WebhookConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;

@Component
public class WebhookSender {

    public static final String ID_HEADER = "X-Webhook-Id";
    public static final String TIMESTAMP_HEADER = "X-Webhook-Timestamp";
    public static final String SIGNATURE_HEADER = "X-Webhook-Signature";

    private final RestClient restClient;

    public WebhookSender(@Qualifier(WebhookConfig.REST_CLIENT) RestClient restClient) {
        this.restClient = restClient;
    }

    // 전송 결과. statusCode는 응답을 받았을 때만
    public record SendOutcome(boolean success, Integer statusCode, String error) {
        static SendOutcome ofStatus(int status) {
            boolean ok = status >= 200 && status < 300;
            return new SendOutcome(ok, status, ok ? null : "HTTP " + status);
        }

        static SendOutcome failure(String error) {
            return new SendOutcome(false, null, error);
        }
    }

    public SendOutcome send(String url, String eventId, long timestamp, String signature, byte[] body) {
        try {
            int status = restClient.post()
                    .uri(URI.create(url))   // 문자열 템플릿이 아니라 URI 그대로 ('{' 같은 글자를 변수로 해석하지 않게)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(ID_HEADER, eventId)
                    .header(TIMESTAMP_HEADER, String.valueOf(timestamp))
                    .header(SIGNATURE_HEADER, signature)
                    .body(body)   // byte[]는 바꾸지 않고 그대로 보낸다 → 서명한 바이트 = 보낸 바이트
                    .exchange((request, response) -> response.getStatusCode().value(), true);
            return SendOutcome.ofStatus(status);
        } catch (ResourceAccessException e) {
            return SendOutcome.failure(isTimeout(e) ? "TIMEOUT" : "CONNECTION_FAILED");
        } catch (RestClientException | IllegalArgumentException e) {
            return SendOutcome.failure("REQUEST_FAILED");
        }
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
