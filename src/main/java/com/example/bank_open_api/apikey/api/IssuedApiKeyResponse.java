package com.example.bank_open_api.apikey.api;

// [무엇] API 키 발급 응답 모양 (JSON)
// [역할] 키 원문(apiKey)을 담는 유일한 응답. 이번 한 번만 보여 주고, 서버는 이후 원문을 다시 알 수 없다
//        → 개발자는 이 응답을 받자마자 안전한 곳에 저장해야 한다
// [누가 쓰나] ApiKeyController (POST /api/v1/apps/{appId}/keys → 201)

import com.example.bank_open_api.apikey.application.ApiKeyService.IssuedApiKey;

import java.time.LocalDateTime;

public record IssuedApiKeyResponse(
        Long id,
        String apiKey,        // 키 원문 (boa_live_...). 이 응답에서만 나간다
        String keyPrefix,     // 화면 표시용 앞 12글자
        LocalDateTime createdAt
) {

    public static IssuedApiKeyResponse from(IssuedApiKey issued) {
        return new IssuedApiKeyResponse(
                issued.apiKey().getId(),
                issued.rawKey(),
                issued.apiKey().getKeyPrefix(),
                issued.apiKey().getCreatedAt());
    }

    // 실수로 로그에 이 객체를 찍어도 원문이 남지 않게 가린다
    @Override
    public String toString() {
        return "IssuedApiKeyResponse[id=" + id + ", keyPrefix=" + keyPrefix + ", apiKey=***]";
    }
}
