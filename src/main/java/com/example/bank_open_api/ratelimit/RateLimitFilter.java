package com.example.bank_open_api.ratelimit;

// [무엇] 오픈 API 호출 제한 검사원 (요청마다 한 번 실행되는 필터)
// [역할] API 키 인증이 끝난 /open/v1/** 요청에서 앱 번호(appId)를 꺼내 RateLimiter로 토큰을 하나 꺼낸다
//        - 통과: 헤더 X-RateLimit-Limit, X-RateLimit-Remaining 을 붙이고 다음으로 넘김
//        - 초과: 429 {"code":"RATE_LIMIT_EXCEEDED", ...} 를 바로 응답하고 끝 (컨트롤러·bank_core까지 가지 않음)
//                헤더 Retry-After(초), X-RateLimit-Limit, X-RateLimit-Remaining: 0
//        - Redis 장애: 헤더 없이 그냥 통과 (fail-open, 로그는 RateLimiter가 남김)
// [누가 쓰나] OpenApiSecurityConfig가 /open/v1/** 규칙표에서 ApiKeyAuthenticationFilter 바로 뒤에 붙인다
//
// ── 왜 @Component로 만들지 않나? ── (ApiKeyAuthenticationFilter와 같은 이유)
// Filter를 빈으로 등록하면 Spring Boot가 "모든 주소"의 서블릿 필터로 자동 등록한다.
// 그러면 /api/v1/** (개발자 포털)에도 끼어들고, API 키 인증보다 먼저 실행되어 appId를 알 수 없다.
// 그래서 OpenApiSecurityConfig에서 new로 만들어 /open/v1/** 규칙표에만, 인증 바로 뒤에 붙인다.
//
// ── 왜 인증 "뒤"인가? ──
// 양동이 기준이 앱(appId)이라 누가 보냈는지 알아야 한다. 또 가짜 키 요청은 인증에서 401로 끝나므로
// 남의 앱 양동이를 비우는 일도 없다.

import com.example.bank_open_api.common.exception.ErrorCode;
import com.example.bank_open_api.common.security.ApiClient;
import com.example.bank_open_api.common.security.SecurityErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    public static final String LIMIT_HEADER = "X-RateLimit-Limit";
    public static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    private final RateLimiter rateLimiter;

    private final SecurityContextHolderStrategy securityContextHolderStrategy =
            SecurityContextHolder.getContextHolderStrategy();

    public RateLimitFilter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // 앞의 ApiKeyAuthenticationFilter가 넣어 둔 호출자 정보
        Authentication authentication = securityContextHolderStrategy.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof ApiClient client)) {
            // 인증 실패 요청은 앞 필터가 401로 끝내므로 보통 여기 오지 않는다.
            // 혹시 오더라도 제한 판단은 하지 않고 뒤 단계(인가 검사)가 막게 둔다
            filterChain.doFilter(request, response);
            return;
        }

        Optional<RateLimitResult> checked = rateLimiter.tryConsume(client.appId());
        if (checked.isEmpty()) {
            // Redis 장애 → fail-open: 제한 없이 통과 (헤더도 붙이지 않음. 모르는 값을 지어내지 않기 위해)
            filterChain.doFilter(request, response);
            return;
        }

        RateLimitResult result = checked.get();
        response.setHeader(LIMIT_HEADER, String.valueOf(result.limit()));

        if (result.allowed()) {
            response.setHeader(REMAINING_HEADER, String.valueOf(result.remaining()));
            filterChain.doFilter(request, response);
            return;
        }

        // 한도 초과 → 429. 이 요청은 컨트롤러로 보내지 않으므로 bank_core도 호출되지 않는다
        log.info("호출 한도 초과: appId={}, keyId={}, retryAfterMs={}",
                client.appId(), client.keyId(), result.retryAfterMillis());
        response.setHeader(REMAINING_HEADER, "0");
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(result.retryAfterSeconds()));
        SecurityErrorWriter.write(response, ErrorCode.RATE_LIMIT_EXCEEDED);
    }
}
