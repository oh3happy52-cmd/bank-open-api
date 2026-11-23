package com.example.bank_open_api.common.config;

// [무엇] Spring Security 설정 (문지기의 규칙표)
// [역할] ① 어떤 주소를 누가 쓸 수 있는지 정함 (회원가입·로그인만 누구나, 나머지는 JWT 필요)
//        ② JWT를 만들고(encoder) 검사하는(decoder) 도구를 준비 (HS256, 비밀 키는 .env의 JWT_SECRET)
//        ③ 비밀번호 해시 도구(BCrypt)를 준비
//        ④ 문지기가 막을 때(401, 403)도 {code, message} 모양으로 응답
//        (bank_core의 SecurityConfig와 같은 구조)
// [누가 쓰나] Spring Security가 모든 요청마다 자동으로 사용. 토큰 발급은 AuthService가 JwtEncoder를,
//            비밀번호 해시는 DeveloperService·AuthService가 PasswordEncoder를 씀
//
// ── 규칙표를 여러 개로 늘리는 방법 (나중에 /open/v1/** API 키 인증을 추가할 때) ──
// Spring Security는 규칙표(SecurityFilterChain)를 여러 개 가질 수 있고,
// 요청이 오면 @Order 숫자가 작은 규칙표부터 "내 담당 주소인가?(securityMatcher)"를 확인해 처음 맞는 하나만 쓴다.
//   - 지금 만드는 JWT 규칙표: securityMatcher 없음(= 모든 주소 담당) + 맨 마지막 순서
//   - 나중에 추가할 API 키 규칙표: securityMatcher("/open/v1/**") + 앞 순서(@Order(1))
// → 이 파일의 기존 규칙은 고치지 않고 새 규칙표만 추가하면 되고,
//   어느 규칙표에도 속하지 않아 보호받지 못하는 주소가 생기지 않는다.

import com.example.bank_open_api.common.exception.ErrorCode;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // HS256 서명에는 256비트(= 32바이트) 이상의 키가 필요하다
    private static final int MIN_SECRET_BYTES = 32;

    // 토큰 서명·검사에 쓰는 비밀 키 (서버만 앎)
    private final SecretKey secretKey;

    // application.properties의 jwt.secret (= .env 또는 환경 변수 JWT_SECRET) 값을 받아 비밀 키를 만든다
    public SecurityConfig(@Value("${jwt.secret}") String jwtSecret) {
        byte[] secretBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        // 짧은 키를 실수로 넣으면 서버가 켜지는 순간 바로 알 수 있게 멈춘다 (키 값 자체는 메시지에 넣지 않음)
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET은 " + MIN_SECRET_BYTES + "바이트 이상이어야 합니다. (현재 " + secretBytes.length + "바이트)"
                            + " .env의 JWT_SECRET 값을 확인하세요.");
        }
        this.secretKey = new SecretKeySpec(secretBytes, "HmacSHA256");
    }

    /**
     * 개발자 포털용 규칙표 (JWT 인증)
     * - POST /api/v1/developers/signup, POST /api/v1/auth/login: 누구나
     * - 그 밖의 모든 주소: 토큰이 있어야 함 (없거나 가짜·만료면 401)
     *
     * 순서를 맨 마지막(LOWEST_PRECEDENCE)으로 두어, 나중에 추가할 /open/v1/** 규칙표가 먼저 검사되게 한다.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public SecurityFilterChain developerPortalSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                // CSRF 보호 끔: 브라우저 쿠키 로그인을 노리는 공격 방어용인데, 우리는 쿠키 대신 토큰을 헤더로 보내서 필요 없음
                .csrf(csrf -> csrf.disable())
                // 세션 안 씀: 서버가 로그인 상태를 기억하지 않고, 요청마다 토큰으로 확인 (STATELESS)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 회원가입·로그인은 토큰이 없는 상태에서 부르는 API라 열어 둠 (POST만)
                        .requestMatchers(HttpMethod.POST, "/api/v1/developers/signup").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .anyRequest().authenticated())
                // 문지기가 막을 때의 응답 모양
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, e) -> writeError(response, ErrorCode.UNAUTHORIZED))   // 401: 토큰 없음
                        .accessDeniedHandler((request, response, e) -> writeError(response, ErrorCode.FORBIDDEN)))         // 403: 권한 부족
                // 요청 헤더의 "Authorization: Bearer 토큰"을 JWT로 검사 (아래 jwtDecoder 사용)
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint((request, response, e) -> writeError(response, ErrorCode.UNAUTHORIZED))  // 401: 가짜·만료 토큰
                        .jwt(jwt -> { }));
        return http.build();
    }

    // 문지기가 막을 때 {code, message} 모양의 JSON을 직접 써서 응답
    // (Controller에 도착하기 전이라 GlobalExceptionHandler가 처리하지 못하므로 여기서 만듦)
    // 메시지는 ErrorCode에 정해 둔 고정 문구뿐이라, 문자열을 이어 붙여도 JSON이 깨지지 않는다
    private static void writeError(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.getStatus().value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":\"" + code.name() + "\",\"message\":\"" + code.getMessage() + "\"}");
    }

    // 토큰 만드는 도구 (로그인 성공 시 AuthService가 사용)
    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));
    }

    // 토큰 검사하는 도구: 서명이 우리 비밀 키로 만든 게 맞는지, 유효 시간이 지나지 않았는지 확인
    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    // 비밀번호 해시 도구 (가입 시 해시 만들기, 로그인 시 입력값과 해시 비교)
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
