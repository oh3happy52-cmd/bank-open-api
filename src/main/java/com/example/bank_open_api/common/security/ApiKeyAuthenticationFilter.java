package com.example.bank_open_api.common.security;

// [무엇] API 키 검사원 (요청마다 한 번 실행되는 필터)
// [역할] /open/v1/** 요청의 X-API-Key 헤더를 검사한다
//        1) 헤더에서 키 원문을 꺼냄
//        2) ApiKeyHasher.sha256Hex로 해시 → DB에서 key_hash로 찾음
//        3) ACTIVE면 SecurityContext에 ApiClient(appId, keyId, keyPrefix)를 넣고 다음으로 넘김
//           헤더 없음 / 못 찾음 / REVOKED면 전부 똑같은 401 {"code":"INVALID_API_KEY", ...}로 바로 응답
// [누가 쓰나] OpenApiSecurityConfig가 /open/v1/** 규칙표에만 붙인다
//
// ── 왜 @Component로 만들지 않나? ──
// Filter를 빈(@Component, @Bean)으로 등록하면 Spring Boot가 "모든 주소"의 서블릿 필터로 자동 등록해 버린다.
// 그러면 /api/v1/** (JWT 쪽) 요청에도 이 검사원이 끼어들어 401을 내게 된다.
// 그래서 OpenApiSecurityConfig 안에서 new로 만들어 /open/v1/** 규칙표에만 붙인다.
//
// ── 로그 규칙 ──
// - 키 원문과 해시는 절대 남기지 않는다 (로그 파일이 새어 나가도 키를 쓸 수 없게)
// - 실패 원인은 reason=missing / not_found / revoked 로만 구분해서 남긴다 (응답은 셋 다 같음)
// - DB에서 찾은 경우(revoked, 성공)에만 keyPrefix·keyId·appId를 남긴다
// - uri 안의 계좌번호는 끝 4자리만 남긴다 (LogMasking.maskPath)
//   (not_found일 때 헤더 값 앞부분을 남기면 그게 남이 잘못 붙여 넣은 진짜 비밀값일 수도 있어서 남기지 않음)
//
// ── 폐기 직후 바로 막히는 이유 ──
// 결과를 기억(캐시)하지 않고 요청마다 DB의 status를 직접 보기 때문에, 폐기 API가 끝난 바로 다음 요청부터 401이 된다.

import com.example.bank_open_api.apikey.application.ApiKeyHasher;
import com.example.bank_open_api.apikey.domain.ApiKey;
import com.example.bank_open_api.apikey.domain.ApiKeyRepository;
import com.example.bank_open_api.apikey.domain.ApiKeyStatus;
import com.example.bank_open_api.common.exception.ErrorCode;
import com.example.bank_open_api.common.logging.LogMasking;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Slf4j
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    // 핀테크 앱이 키를 실어 보내는 헤더 이름
    public static final String API_KEY_HEADER = "X-API-Key";

    // 인증된 API 호출자에게 붙이는 권한 이름 (지금은 표시용. 나중에 권한을 나눌 때 기준이 됨)
    private static final String ROLE_API_CLIENT = "ROLE_API_CLIENT";

    private final ApiKeyRepository apiKeyRepository;

    // SecurityContextHolder를 직접 부르는 대신 "보관 방식(strategy)"을 통해 쓰는 것이 Spring Security 6+ 권장 방식
    private final SecurityContextHolderStrategy securityContextHolderStrategy =
            SecurityContextHolder.getContextHolderStrategy();

    public ApiKeyAuthenticationFilter(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String rawKey = request.getHeader(API_KEY_HEADER);

        // 1) 헤더 없음 (빈 값·공백만 있는 것도 "없음"으로 본다)
        if (rawKey == null || rawKey.isBlank()) {
            reject(request, response, "missing", null);
            return;
        }

        // 2) 해시로 찾기. 원문은 이 메서드 밖으로 나가지 않고, 로그에도 쓰지 않는다
        Optional<ApiKey> found = apiKeyRepository.findByKeyHash(ApiKeyHasher.sha256Hex(rawKey));
        if (found.isEmpty()) {
            reject(request, response, "not_found", null);
            return;
        }

        ApiKey apiKey = found.get();

        // 3) 폐기된 키
        if (apiKey.getStatus() != ApiKeyStatus.ACTIVE) {
            reject(request, response, "revoked", apiKey);
            return;
        }

        // 4) 성공: "이 요청은 이 앱의 이 키로 들어왔다"를 SecurityContext에 기록
        //    credentials(비밀번호 자리)는 null → 키 원문·해시를 인증 정보에 들고 다니지 않음
        ApiClient client = new ApiClient(apiKey.getAppId(), apiKey.getId(), apiKey.getKeyPrefix());
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                client, null, List.of(new SimpleGrantedAuthority(ROLE_API_CLIENT)));

        SecurityContext context = securityContextHolderStrategy.createEmptyContext();
        context.setAuthentication(authentication);
        securityContextHolderStrategy.setContext(context);
        // 세션이 STATELESS라 어디에도 저장하지 않는다. 요청이 끝나면 Spring Security가 자동으로 비운다

        log.debug("API 키 인증 성공: appId={}, keyId={}, keyPrefix={}",
                client.appId(), client.keyId(), client.keyPrefix());

        filterChain.doFilter(request, response);
    }

    // 실패 응답: 원인과 상관없이 항상 같은 401 본문. 원인은 로그에만 남김
    // apiKey는 DB에서 찾은 경우(revoked)에만 넘어온다 → 그때만 keyPrefix 등을 남김
    private void reject(HttpServletRequest request, HttpServletResponse response, String reason, ApiKey apiKey)
            throws IOException {
        // uri의 계좌번호 자리는 끝 4자리만 남긴다 (예: /open/v1/accounts/****7890)
        String maskedUri = LogMasking.maskPath(request.getRequestURI());
        if (apiKey == null) {
            log.info("API 키 인증 실패: reason={}, method={}, uri={}",
                    reason, request.getMethod(), maskedUri);
        } else {
            log.info("API 키 인증 실패: reason={}, method={}, uri={}, appId={}, keyId={}, keyPrefix={}",
                    reason, request.getMethod(), maskedUri,
                    apiKey.getAppId(), apiKey.getId(), apiKey.getKeyPrefix());
        }
        // 혹시 앞에서 무언가 들어 있었더라도 인증 정보가 남지 않게 비운다
        securityContextHolderStrategy.clearContext();
        SecurityErrorWriter.write(response, ErrorCode.INVALID_API_KEY);
    }
}
