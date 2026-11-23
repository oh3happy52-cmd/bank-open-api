package com.example.bank_open_api.developer.api;

// [무엇] 개발자 API 창구
// [역할] 회원가입 요청을 받아 DeveloperService에 넘기고, 로그인한 개발자의 내 정보를 돌려줌
// [누가 쓰나] 개발자 포털(프런트엔드), curl
//            signup은 토큰 없이, /me는 "Authorization: Bearer 토큰"이 있어야 호출 가능 (SecurityConfig)

import com.example.bank_open_api.common.security.LoginDeveloper;
import com.example.bank_open_api.developer.application.DeveloperService;
import com.example.bank_open_api.developer.domain.Developer;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/developers")
@RequiredArgsConstructor
public class DeveloperController {

    private final DeveloperService developerService;

    /**
     * 회원가입
     * POST /api/v1/developers/signup
     * - 201: 가입 성공 (본문: 가입한 개발자 정보)
     * - 400: 입력 오류 (이메일 형식, 비밀번호 8자 미만·72바이트 초과 등)
     * - 409: 이미 가입된 이메일
     */
    @PostMapping("/signup")
    public ResponseEntity<DeveloperResponse> signup(@Valid @RequestBody SignupRequest request) {
        Developer developer = developerService.signup(request.email(), request.password(), request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(DeveloperResponse.from(developer));
    }

    /**
     * 내 정보
     * GET /api/v1/developers/me
     * - 200: 토큰 주인의 정보 (id, email, name, createdAt. 비밀번호 해시는 없음)
     * - 401: 토큰 없음·가짜·만료
     */
    @GetMapping("/me")
    public DeveloperResponse me(@AuthenticationPrincipal Jwt jwt) {   // 문지기가 검사를 마친 토큰
        return DeveloperResponse.from(developerService.getMe(LoginDeveloper.from(jwt)));
    }
}
