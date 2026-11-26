package com.example.bank_open_api.common.config;

// [무엇] 핀테크 앱용 오픈 API(/open/v1/**)의 문지기 규칙표 (API 키 인증)
// [역할] /open/v1/** 로 오는 요청만 담당하고, 전부 X-API-Key 헤더의 API 키가 있어야 통과시킨다
//        통과한 요청은 사용량을 기록하고(UsageLogFilter), 앱별 호출 제한(RateLimitFilter, 초과 시 429)을 검사한다
//        - CSRF 끔, 세션 STATELESS (요청마다 키로 확인)
//        - JWT(oauth2ResourceServer)는 쓰지 않는다 → 여기서는 Authorization: Bearer 토큰을 보내도 소용없음
// [누가 쓰나] Spring Security가 /open/v1/** 요청마다 자동으로 사용
//
// ── 규칙표가 두 개일 때 순서 ──
// 요청이 오면 @Order 숫자가 작은 규칙표부터 "내 담당 주소인가?(securityMatcher)"를 보고, 처음 맞는 하나만 쓴다.
//   1번: 이 규칙표 — securityMatcher("/open/v1/**"), @Order(1)       → /open/v1/** 는 여기서 끝
//   마지막: SecurityConfig의 JWT 규칙표 — 담당 주소 제한 없음          → 그 밖의 모든 주소
// → 기존 JWT 규칙표는 한 줄도 고치지 않았고, 반대로 /api/v1/** 에 API 키만 보내면 JWT 규칙표가 401로 막는다.
//
// ── 이 규칙표 안의 검사 순서 ──
//   ① ApiKeyAuthenticationFilter : 키 검사. 실패 → 401 (여기서 끝. 양동이도, 사용량 기록도 건드리지 않음)
//   ② UsageLogFilter             : 시간 재기 시작 → ③ 이후가 끝나면 finally에서 결과를 기록 (비동기 저장)
//                                  ③보다 바깥에 있어서 ③이 429로 끝내도 기록된다
//   ③ RateLimitFilter            : 앱(appId) 양동이에서 토큰 1개. 없으면 → 429 (컨트롤러·bank_core까지 안 감)
//   ④ (Spring Security 기본) 인가 검사 → 컨트롤러

import com.example.bank_open_api.apikey.domain.ApiKeyRepository;
import com.example.bank_open_api.common.exception.ErrorCode;
import com.example.bank_open_api.common.security.ApiKeyAuthenticationFilter;
import com.example.bank_open_api.common.security.SecurityErrorWriter;
import com.example.bank_open_api.ratelimit.RateLimitFilter;
import com.example.bank_open_api.ratelimit.RateLimiter;
import com.example.bank_open_api.usage.application.UsageLogRecorder;
import com.example.bank_open_api.usage.web.EndpointTemplateResolver;
import com.example.bank_open_api.usage.web.UsageLogFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

import java.time.Clock;

@Configuration
public class OpenApiSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain openApiSecurityFilterChain(HttpSecurity http, ApiKeyRepository apiKeyRepository,
                                                          RateLimiter rateLimiter,
                                                          UsageLogRecorder usageLogRecorder,
                                                          EndpointTemplateResolver endpointTemplateResolver,
                                                          Clock clock) throws Exception {
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
                // 사용량 기록원: API 키 검사원 뒤 (appId를 알아야 하므로), 호출 제한 검사원 앞 (429도 기록하려고)
                // (마찬가지로 new로 만들어 이 규칙표에만 붙임. 저장기·주소 틀 도구·시계만 빈으로 받아서 넘겨 준다)
                .addFilterAfter(new UsageLogFilter(usageLogRecorder, endpointTemplateResolver, clock),
                        ApiKeyAuthenticationFilter.class)
                // 호출 제한 검사원: 사용량 기록원 바로 뒤 (인증된 ApiClient에서 appId를 꺼내야 하므로 인증보다 뒤)
                .addFilterAfter(new RateLimitFilter(rateLimiter), UsageLogFilter.class)
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
