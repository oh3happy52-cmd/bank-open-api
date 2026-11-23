package com.example.bank_open_api.developer.application;

// [무엇] 로그인 서비스
// [역할] ① 이메일로 개발자 찾기 ② 비밀번호를 BCrypt 해시와 비교 ③ 맞으면 JWT(1시간) 발급
//        (bank_core의 AuthService와 같은 구조)
//
//        보안 규칙:
//        - 응답: "이메일 없음"과 "비밀번호 틀림"을 같은 오류(401 INVALID_CREDENTIALS)로 돌려줌
//                → "이 이메일은 가입되어 있다"는 정보를 주지 않기 위해
//        - 시간: 이메일이 없어도 BCrypt 비교를 한 번 해서 응답 시간을 맞춤
//                → 응답이 빠른지 느린지로 이메일 존재 여부를 알아내는 것 방지
//        - 로그: 서버 로그에는 원인을 구분해서 남김 (reason=email_not_found / wrong_password)
//                이메일은 가려서(ab***@example.com) 남기고, 비밀번호는 절대 남기지 않음
// [누가 쓰나] AuthController (POST /api/v1/auth/login)

import com.example.bank_open_api.common.exception.BusinessException;
import com.example.bank_open_api.common.exception.ErrorCode;
import com.example.bank_open_api.common.validation.MaxUtf8BytesValidator;
import com.example.bank_open_api.developer.domain.Developer;
import com.example.bank_open_api.developer.domain.DeveloperRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Service
public class AuthService {

    // BCrypt가 실제로 쓰는 최대 길이. 이보다 긴 비밀번호로는 가입할 수 없으므로 로그인도 무조건 실패
    // (BCrypt는 72바이트 뒤를 잘라 버리기 때문에, 그대로 비교하면 "진짜 비밀번호 + 아무 글자"도 통과할 수 있음 → 먼저 막음)
    private static final int BCRYPT_MAX_BYTES = 72;

    private final DeveloperRepository developerRepository;
    private final PasswordEncoder passwordEncoder;   // BCrypt 비교 도구 (SecurityConfig에서 만든 것)
    private final JwtEncoder jwtEncoder;             // 토큰 만드는 도구 (SecurityConfig에서 만든 것)
    private final long expirationMinutes;            // 토큰 유효 시간 (application.properties: 60분)

    // 없는 이메일일 때 비교용으로 쓰는 가짜 해시 (서버가 켜질 때 한 번 만듦)
    // BCrypt 비교는 일부러 느리게(약 0.1초) 만들어져 있어서, 이메일이 없을 때 비교를 건너뛰면
    // "응답이 빠르면 없는 이메일"이라는 힌트가 생김 → 없을 때도 같은 비교를 해서 시간을 맞춤
    private final String dummyPasswordHash;

    public AuthService(DeveloperRepository developerRepository,
                       PasswordEncoder passwordEncoder,
                       JwtEncoder jwtEncoder,
                       @Value("${jwt.expiration-minutes}") long expirationMinutes) {
        this.developerRepository = developerRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.expirationMinutes = expirationMinutes;
        this.dummyPasswordHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    // 로그인 결과: 토큰과 유효 시간(초)
    public record LoginResult(String accessToken, long expiresInSeconds) {
    }

    @Transactional(readOnly = true)
    public LoginResult login(String email, String rawPassword) {
        String normalizedEmail = EmailNormalizer.normalize(email);
        String maskedEmail = EmailNormalizer.mask(normalizedEmail);

        // 0. 72바이트를 넘는 비밀번호는 가입 단계에서 막혀 있으므로 맞을 수가 없음 → 바로 실패
        if (MaxUtf8BytesValidator.utf8Length(rawPassword) > BCRYPT_MAX_BYTES) {
            log.warn("로그인 실패: reason=password_too_long, email={}", maskedEmail);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        // 1. 이메일로 개발자 찾기
        Developer developer = developerRepository.findByEmail(normalizedEmail).orElse(null);

        // 2. 비밀번호 비교 (이메일이 없어도 가짜 해시로 항상 비교 → 응답 시간을 맞춤)
        String hashToCompare = (developer != null) ? developer.getPasswordHash() : dummyPasswordHash;
        boolean passwordMatches = passwordEncoder.matches(rawPassword, hashToCompare);

        // 3. 실패: 로그에는 원인을 구분해서, 응답은 같은 오류 하나로
        if (developer == null) {
            log.warn("로그인 실패: reason=email_not_found, email={}", maskedEmail);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (!passwordMatches) {
            log.warn("로그인 실패: reason=wrong_password, developerId={}, email={}", developer.getId(), maskedEmail);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        // 4. 토큰 내용(claims) 만들기
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("bank-open-api")                          // 누가 발급했나
                .subject(String.valueOf(developer.getId()))       // 누구 토큰인가 (개발자 번호)
                .claim("email", developer.getEmail())
                .issuedAt(now)                                    // 발급 시각
                .expiresAt(now.plus(expirationMinutes, ChronoUnit.MINUTES))   // 만료 시각 (1시간 뒤)
                .build();

        // 5. HS256(비밀 키 방식)으로 서명해서 토큰 문자열 만들기
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        log.info("로그인 성공: developerId={}", developer.getId());
        return new LoginResult(token, expirationMinutes * 60);
    }
}
