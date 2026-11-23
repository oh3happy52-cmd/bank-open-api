package com.example.bank_open_api.developer.application;

// [무엇] 개발자 회원가입·내 정보 서비스
// [역할] ① 회원가입: 이메일 정리(소문자) → 중복 확인(409) → 비밀번호를 BCrypt 해시로 바꿔 저장
//        ② 내 정보: 토큰의 개발자 번호로 조회
// [누가 쓰나] DeveloperController (POST /api/v1/developers/signup, GET /api/v1/developers/me)

import com.example.bank_open_api.common.exception.BusinessException;
import com.example.bank_open_api.common.exception.ErrorCode;
import com.example.bank_open_api.common.security.LoginDeveloper;
import com.example.bank_open_api.developer.domain.Developer;
import com.example.bank_open_api.developer.domain.DeveloperRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeveloperService {

    private final DeveloperRepository developerRepository;
    private final PasswordEncoder passwordEncoder;   // BCrypt 해시 도구 (SecurityConfig에서 만든 것)

    @Transactional
    public Developer signup(String email, String rawPassword, String name) {
        String normalizedEmail = EmailNormalizer.normalize(email);

        // 1. 이미 가입된 이메일이면 409
        //    (동시에 두 요청이 들어와 여기를 둘 다 통과해도, DB UNIQUE 제약이 하나를 막고
        //     GlobalExceptionHandler가 같은 409로 바꿔 준다)
        if (developerRepository.existsByEmail(normalizedEmail)) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }

        // 2. 비밀번호는 BCrypt 해시로만 저장 (원래 비밀번호는 이 메서드가 끝나면 어디에도 남지 않음)
        String passwordHash = passwordEncoder.encode(rawPassword);
        Developer developer = developerRepository.save(Developer.create(normalizedEmail, passwordHash, name.trim()));

        log.info("개발자 가입: developerId={}", developer.getId());
        return developer;
    }

    @Transactional(readOnly = true)
    public Developer getMe(LoginDeveloper loginDeveloper) {
        // 토큰은 진짜인데 그 사이 개발자가 사라진 경우 → 더 이상 유효한 로그인이 아니므로 401
        return developerRepository.findById(loginDeveloper.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }
}
