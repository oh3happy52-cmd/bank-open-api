package com.example.bank_open_api.common.security;

// [무엇] 요청한 개발자 정보 (토큰에서 꺼낸 개발자 번호)
// [역할] Controller가 문지기가 검사를 마친 토큰(Jwt)에서 만들어 Service에 넘김 (bank_core의 LoginMember와 같은 역할)
// [누가 쓰나] DeveloperController(/me), DeveloperService

import org.springframework.security.oauth2.jwt.Jwt;

public record LoginDeveloper(
        Long id   // 개발자 번호 (토큰의 sub)
) {

    // 문지기가 서명·만료 검사를 마친 토큰에서 개발자 정보 꺼내기
    public static LoginDeveloper from(Jwt jwt) {
        return new LoginDeveloper(Long.valueOf(jwt.getSubject()));
    }
}
