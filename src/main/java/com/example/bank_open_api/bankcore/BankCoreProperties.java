package com.example.bank_open_api.bankcore;

// [무엇] bank_core 접속 설정값 묶음
// [역할] application.properties의 bank-core.* 값(주소, 연동 계정, 제한 시간)을 한 객체로 받아 둔다
//        실제 값은 .env(BANK_CORE_BASE_URL, BANK_CORE_USERNAME, BANK_CORE_PASSWORD)에서 온다
// [누가 쓰나] BankCoreConfig(RestClient 만들 때), BankCoreTokenProvider(로그인할 때)

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "bank-core")
public record BankCoreProperties(
        String baseUrl,           // 예: http://localhost:8080
        String username,          // bank_core 연동 전용 회원의 loginId
        String password,          // 그 회원의 비밀번호 (원문. 로그에 절대 남기지 않는다)
        Duration connectTimeout,  // 연결 제한 시간 (3초)
        Duration readTimeout      // 응답 제한 시간 (5초)
) {

    // 연동 계정이 설정되어 있는가 (둘 중 하나라도 비어 있으면 로그인을 시도하지 않음)
    public boolean hasCredentials() {
        return username != null && !username.isBlank() && password != null && !password.isBlank();
    }

    // record는 toString()에 모든 칸을 그대로 찍는다 → 누가 이 객체를 로그에 찍어도 비밀번호가 나가지 않게 가림
    @Override
    public String toString() {
        return "BankCoreProperties[baseUrl=" + baseUrl + ", username=" + username
                + ", password=****, connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + "]";
    }
}
