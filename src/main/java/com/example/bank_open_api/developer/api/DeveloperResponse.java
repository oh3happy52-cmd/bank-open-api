package com.example.bank_open_api.developer.api;

// [무엇] 개발자 정보 응답 모양 (JSON)
// [역할] 엔티티(Developer)에서 밖에 보여 줘도 되는 값만 골라 담음
//        비밀번호 해시(passwordHash)는 일부러 넣지 않는다
// [누가 쓰나] DeveloperController (회원가입 201 응답, /me 응답)

import com.example.bank_open_api.developer.domain.Developer;

import java.time.LocalDateTime;

public record DeveloperResponse(
        Long id,
        String email,
        String name,
        LocalDateTime createdAt
) {

    public static DeveloperResponse from(Developer developer) {
        return new DeveloperResponse(
                developer.getId(),
                developer.getEmail(),
                developer.getName(),
                developer.getCreatedAt());
    }
}
