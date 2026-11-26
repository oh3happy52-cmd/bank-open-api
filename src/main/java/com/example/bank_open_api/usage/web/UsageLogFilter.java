package com.example.bank_open_api.usage.web;

// [무엇] 오픈 API 사용량 기록원 (요청마다 한 번 실행되는 필터)
// [역할] API 키 인증을 통과한 /open/v1/** 요청의 결과를 한 건씩 기록한다
//        1) 앞의 ApiKeyAuthenticationFilter가 넣어 둔 ApiClient(appId, keyId)를 꺼냄
//        2) 시작 시각(Clock, 한국 시간)과 시작 시점(nanoTime)을 잰다
//        3) chain.doFilter → 뒤의 RateLimitFilter·컨트롤러가 처리
//        4) finally: 걸린 시간, 상태 코드, 주소 틀을 모아 UsageLogRecorder에 넘김 (저장은 다른 스레드)
//        - 429도 기록된다: RateLimitFilter보다 "앞"(바깥)에 있어서, 뒤에서 429로 끝나도 여기 finally는 실행된다
//        - 401은 기록되지 않는다: 인증 실패는 ApiKeyAuthenticationFilter에서 끝나 여기까지 오지 않는다
//          (앱을 모르므로 어느 앱의 기록인지 정할 수 없다)
//        - 예외가 밖으로 나오면: 응답 상태 코드가 아직 200일 수 있어 믿을 수 없으므로 500으로 기록하고, 예외는 그대로 던진다
// [누가 쓰나] OpenApiSecurityConfig가 /open/v1/** 규칙표에서 ApiKeyAuthenticationFilter 뒤, RateLimitFilter 앞에 붙인다
//
// ── 왜 @Component로 만들지 않나? ── (ApiKeyAuthenticationFilter·RateLimitFilter와 같은 이유)
// Filter를 빈으로 등록하면 Spring Boot가 "모든 주소"의 서블릿 필터로 자동 등록해, 인증보다 먼저 실행되어 버린다.
//
// ── 로그·저장 규칙 ──
// 실제 주소(request URI)는 어디에도 쓰지 않는다. 주소 틀만 쓴다 → 계좌번호가 DB·로그에 남지 않는다.

import com.example.bank_open_api.common.security.ApiClient;
import com.example.bank_open_api.usage.application.UsageLogEntry;
import com.example.bank_open_api.usage.application.UsageLogRecorder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;

@Slf4j
public class UsageLogFilter extends OncePerRequestFilter {

    private final UsageLogRecorder recorder;
    private final EndpointTemplateResolver endpointResolver;
    private final Clock clock;

    private final SecurityContextHolderStrategy securityContextHolderStrategy =
            SecurityContextHolder.getContextHolderStrategy();

    public UsageLogFilter(UsageLogRecorder recorder, EndpointTemplateResolver endpointResolver, Clock clock) {
        this.recorder = recorder;
        this.endpointResolver = endpointResolver;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        Authentication authentication = securityContextHolderStrategy.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof ApiClient client)) {
            // 인증 실패 요청은 앞 필터가 401로 끝내므로 보통 여기 오지 않는다. 앱을 모르면 기록하지 않는다
            filterChain.doFilter(request, response);
            return;
        }

        LocalDateTime requestedAt = LocalDateTime.now(clock);   // 기록용 시각 (한국 시간, 테스트에서 고정 가능)
        long startedNanos = System.nanoTime();                   // 걸린 시간 측정용 (시계 변경에 영향 없음)
        boolean completed = false;
        try {
            filterChain.doFilter(request, response);
            completed = true;
        } finally {
            long latencyMs = (System.nanoTime() - startedNanos) / 1_000_000;
            int statusCode = completed ? response.getStatus() : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
            record(client, request, statusCode, latencyMs, requestedAt);
        }
    }

    // 기록 준비 중에 무슨 일이 있어도 API 응답(또는 원래 예외)에 영향을 주지 않게 감싼다
    private void record(ApiClient client, HttpServletRequest request, int statusCode,
                        long latencyMs, LocalDateTime requestedAt) {
        try {
            recorder.record(new UsageLogEntry(
                    client.appId(),
                    client.keyId(),
                    request.getMethod(),
                    endpointResolver.resolve(request),
                    statusCode,
                    latencyMs,
                    requestedAt));
        } catch (RuntimeException e) {
            log.warn("사용량 기록 버림(기록 준비 실패): appId={}, error={}", client.appId(), e.getClass().getSimpleName());
        }
    }
}
