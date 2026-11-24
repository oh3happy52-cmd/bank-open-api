package com.example.bank_open_api.bankcore;

// [무엇] bank_core를 부르는 HTTP 도구(RestClient) 준비
// [역할] bank_core 주소를 기본 주소로 넣고, 연결 3초 / 응답 5초 제한 시간을 건 RestClient를 하나 만든다
// [누가 쓰나] BankCoreTokenProvider(로그인), BankCoreClient(계좌 조회)
//
// RestClient.builder()로 직접 만드는 이유:
// Spring Boot 4는 RestClient 자동 준비가 별도 라이브러리(spring-boot-restclient)로 분리돼 있어서,
// 라이브러리를 늘리지 않고 직접 만든다 (bank_core의 InterbankClient와 같은 방식)

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(BankCoreProperties.class)
public class BankCoreConfig {

    // 다른 곳에서 RestClient를 또 만들어도 헷갈리지 않게 이름을 붙여 둔다
    public static final String REST_CLIENT = "bankCoreRestClient";

    @Bean(REST_CLIENT)
    public RestClient bankCoreRestClient(BankCoreProperties properties) {
        // Java 기본 HTTP 연결(HttpURLConnection) 사용
        //  - connectTimeout: bank_core와 연결(TCP 접속)을 맺는 데 걸리는 최대 시간
        //  - readTimeout: 요청을 보낸 뒤 응답이 오기를 기다리는 최대 시간
        //  넘으면 SocketTimeoutException → BankCoreFailures가 504 BANK_CORE_TIMEOUT으로 바꾼다
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
