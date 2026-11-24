package com.example.bank_open_api.common.config;

// [무엇] 핀테크 앱용 오픈 API(/open/v1/**)의 문지기 규칙표 (API 키 인증)
// [역할] /open/v1/** 로 오는 요청만 담당하고, 전부 X-API-Key 헤더의 API 키가 있어야 통과시킨다
//        - CSRF 끔, 세션 STATELESS (요청마다 키로 확인)
//        - JWT(oauth2ResourceServer)는 쓰지 않는다 → 여기서는 Authorization: Bearer 토큰을 보내도 소용없음
// [누가 쓰나] Spring Security가 /open/v1/** 요청마다 자동으로 사용
//
// ── 규칙표가 두 개일 때 순서 ──
// 요청이 오면 @Order 숫자가 작은 규칙표부터 "내 담당 주소인가?(securityMatcher)"를 보고, 처음 맞는 하나만 쓴다.
//   1번: 이 규칙표 — securityMatcher("/open/v1/**"), @Order(1)       → /open/v1/** 는 여기서 끝
//   마지막: SecurityConfig의 JWT 규칙표 — 담당 주소 제한 없음          → 그 밖의 모든 주소
// → 기존 JWT 규칙표는 한 줄도 고치지 않았고, 반대로 /api/v1/** 에 API 키만 보내면 JWT 규칙표가 401로 막는다.

import com.example.bank_open_api.apikey.domain.ApiKeyRepository;
import com.example.bank_open_api.common.exception.ErrorCode;
import com.example.bank_open_api.common.security.ApiKeyAuthenticationFilter;
import com.example.bank_open_api.common.security.SecurityErrorWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
public class OpenApiSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain openApiSecurityFilterChain(HttpSecurity http, ApiKeyRepository apiKeyRepository)
            throws Exception {
        http
                // 이 규칙표가 담당하는 주소
                .securityMatcher("/open/v1/**")
                // CSRF 끔: 쿠키가 아니라 헤더(X-API-Key)로 인증하므로 필요 없음
                .csrf(csrf -> csrf.disable())
                // 세션 안 씀: 서버가 상태를 기억하지 않고 요청마다 키로 확인
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 담당 주소 전부 인증 필요
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                // API 키 검사원: "익명 사용자" 표시를 붙이는 단계보다 앞에서 실행
                // (new로 만들어 이 규칙표에만 붙임 — 빈으로 등록하면 모든 주소에 끼어든다. 필터 파일 주석 참고)
                .addFilterBefore(new ApiKeyAuthenticationFilter(apiKeyRepository), AnonymousAuthenticationFilter.class)
                // 안전망: 검사원은 실패하면 직접 401을 쓰고 끝내므로 보통은 여기까지 오지 않는다.
                //         그래도 인증 없이 통과하는 일이 생기면 같은 401 INVALID_API_KEY 모양으로 응답
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, e) ->
                                SecurityErrorWriter.write(response, ErrorCode.INVALID_API_KEY))
                        .accessDeniedHandler((request, response, e) ->
                                SecurityErrorWriter.write(response, ErrorCode.FORBIDDEN)));
        // oauth2ResourceServer(JWT)는 일부러 설정하지 않는다
        return http.build();
    }
}
