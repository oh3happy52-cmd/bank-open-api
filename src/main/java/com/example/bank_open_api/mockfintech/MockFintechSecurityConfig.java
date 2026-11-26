package com.example.bank_open_api.mockfintech;

// [무엇] 모의 핀테크 서버(/mock-fintech/**) 전용 문지기 규칙표
// [역할] /mock-fintech/** 요청만 담당한다 (@Order(2): API 키 규칙표(1) 다음, JWT 규칙표(마지막) 앞)
//        - mock-fintech.enabled=true  → 인증 없이 누구나 통과 (웹훅을 "보내는 쪽"인 우리 서버가 토큰 없이 부르므로)
//        - mock-fintech.enabled=false → 모든 요청에 404 NOT_FOUND를 바로 응답 (주소가 없는 것처럼)
// [누가 쓰나] Spring Security
//
// ── 왜 규칙표를 꺼짐일 때도 두나? ──
//   컨트롤러만 빼 버리면 /mock-fintech/** 요청이 마지막 JWT 규칙표로 넘어가 401이 난다 ("뭔가 있구나"라는 힌트).
//   꺼짐일 때도 이 규칙표가 먼저 받아서 404로 끝내면, 주소가 아예 없는 것과 똑같이 보인다.
//   permitAll 규칙은 켜짐일 때만 만들어지므로, 운영(꺼짐)에서 인증 없는 구멍이 열려 있지 않다.
//
// 켜져 있을 때 관리 API(config, faults, received)도 인증이 없다 → 로컬 실험 전용. 운영에서는 반드시 false

import com.example.bank_open_api.common.exception.ErrorCode;
import com.example.bank_open_api.common.security.SecurityErrorWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class MockFintechSecurityConfig {

    public static final String PATH = "/mock-fintech/**";

    @Bean
    @Order(2)
    public SecurityFilterChain mockFintechSecurityFilterChain(
            HttpSecurity http, @Value("${mock-fintech.enabled:false}") boolean enabled) throws Exception {
        http
                .securityMatcher(PATH)
                .csrf(csrf -> csrf.disable())   // 서버끼리 부르는 API. 쿠키 로그인을 쓰지 않음
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (enabled) {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        } else {
            // 꺼짐: 익명 요청을 모두 막고, 막을 때 401 대신 404를 쓴다
            http.authorizeHttpRequests(auth -> auth.anyRequest().denyAll())
                    .exceptionHandling(exception -> exception
                            .authenticationEntryPoint((request, response, e) ->
                                    SecurityErrorWriter.write(response, ErrorCode.NOT_FOUND))
                            .accessDeniedHandler((request, response, e) ->
                                    SecurityErrorWriter.write(response, ErrorCode.NOT_FOUND)));
        }
        return http.build();
    }
}
