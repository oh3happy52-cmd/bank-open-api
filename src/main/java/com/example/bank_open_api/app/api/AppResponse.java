package com.example.bank_open_api.app.api;

// [무엇] 앱 정보 응답 모양 (JSON)
// [역할] 엔티티(App)에서 밖에 보여 줄 값만 골라 담음
// [누가 쓰나] AppController (등록 201 응답, 목록, 상세)

import com.example.bank_open_api.app.domain.App;

import java.time.LocalDateTime;

public record AppResponse(
        Long id,
        String name,
        String description,   // 등록할 때 비워 두었으면 null
        LocalDateTime createdAt
) {

    public static AppResponse from(App app) {
        return new AppResponse(app.getId(), app.getName(), app.getDescription(), app.getCreatedAt());
    }
}
