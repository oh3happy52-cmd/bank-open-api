package com.example.bank_open_api.webhook.config;

// [무엇] 웹훅 설정 등록 + 웹훅 전용 HTTP 도구(RestClient)
// [역할] ① webhook.* 설정값(WebhookProperties)을 쓸 수 있게 켠다
//        ② 받는 쪽(핀테크 앱) 서버를 부를 RestClient를 bank_core용과 따로 만든다
//           - 연결 2초 / 응답 3초 제한 (받는 쪽이 느려도 일꾼이 오래 붙잡히지 않게)
//           - 리다이렉트(3xx)를 따라가지 않는다: 등록한 주소가 아닌 다른 곳(내부망 주소 등)으로
//             서명된 알림이 흘러가지 않게. 3xx는 "실패"로 기록하고 재시도한다
// [누가 쓰나] Spring이 서버 시작 때 읽음. RestClient는 WebhookSender가 씀

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.HttpURLConnection;

@Configuration
@EnableConfigurationProperties(WebhookProperties.class)
public class WebhookConfig {

    public static final String REST_CLIENT = "webhookRestClient";

    @Bean(REST_CLIENT)
    public RestClient webhookRestClient(WebhookProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);   // 어떤 메서드든 리다이렉트를 따라가지 않음
            }
        };
        requestFactory.setConnectTimeout(properties.http().connectTimeout());
        requestFactory.setReadTimeout(properties.http().readTimeout());

        // baseUrl 없음: 앱마다 주소가 다르다
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}
