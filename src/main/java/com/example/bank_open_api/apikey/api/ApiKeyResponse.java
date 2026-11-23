package com.example.bank_open_api.apikey.api;

// [무엇] API 키 목록 응답 모양 (JSON)
// [역할] 엔티티(ApiKey)에서 밖에 보여 줘도 되는 값만 담는다
//        키 원문(apiKey)과 해시(keyHash)는 칸이 아예 없다 → 실수로도 응답에 나갈 수 없다
// [누가 쓰나] ApiKeyController (GET /api/v1/apps/{appId}/keys)

import com.example.bank_open_api.apikey.domain.ApiKey;
import com.example.bank_open_api.apikey.domain.ApiKeyStatus;

import java.time.LocalDateTime;

public record ApiKeyResponse(
        Long id,
        String keyPrefix,         // 화면 표시용 앞 12글자 (예: boa_live_Xy3)
        ApiKeyStatus status,      // ACTIVE / REVOKED
        LocalDateTime createdAt,
        LocalDateTime revokedAt   // ACTIVE면 null
) {

    public static ApiKeyResponse from(ApiKey apiKey) {
        return new ApiKeyResponse(
                apiKey.getId(),
                apiKey.getKeyPrefix(),
                apiKey.getStatus(),
                apiKey.getCreatedAt(),
                apiKey.getRevokedAt());
    }
}
