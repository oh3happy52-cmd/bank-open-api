package com.example.bank_open_api.apikey.application;

// [무엇] API 키 해시 도구 (SHA-256 → 16진수 64글자)
// [역할] 키 원문을 DB에 저장할 해시로 바꾼다. 같은 원문이면 항상 같은 해시가 나온다
// [누가 쓰나] ApiKeyGenerator(발급할 때), ApiKeyAuthenticationFilter(요청 헤더의 키로 DB를 찾을 때)
//
// ── 비밀번호는 BCrypt인데, API 키는 왜 SHA-256인가? ──
// 1) 추측이 불가능하다
//    비밀번호는 사람이 정해서 짧고 뻔할 수 있다("password1" 등). 그래서 일부러 느린 BCrypt로
//    "하나씩 넣어 보는" 공격(무차별 대입)을 늦춘다.
//    API 키는 SecureRandom으로 만든 무작위 32바이트(2^256가지)라서, 해시가 아무리 빨라도
//    전부 넣어 보는 것이 우주의 나이보다 오래 걸린다. 느리게 만들 이유가 없다.
// 2) 요청마다 검사해야 해서 빨라야 한다
//    API 키는 로그인처럼 한 번이 아니라 /open/v1/** 요청이 올 때마다 검사한다.
//    BCrypt는 한 번에 약 0.1초라 초당 수백 건만 와도 서버가 버티지 못한다. SHA-256은 1마이크로초 수준이다.
// 3) 해시값으로 바로 찾을 수 있어야 한다
//    BCrypt는 매번 다른 소금(salt)을 섞어 같은 입력도 결과가 달라진다 → WHERE key_hash = ? 로 찾을 수 없고
//    모든 키와 하나씩 비교해야 한다.
//    SHA-256은 같은 입력이면 항상 같은 결과라서 UNIQUE 인덱스(uk_api_keys_key_hash)로 한 번에 찾는다.

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ApiKeyHasher {

    private ApiKeyHasher() {
    }

    // 키 원문 → SHA-256 해시(소문자 16진수 64글자)
    public static String sha256Hex(String rawKey) {
        try {
            // MessageDigest는 여러 스레드가 함께 쓰면 안전하지 않아서 부를 때마다 새로 만든다 (만드는 비용은 아주 작음)
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // 모든 Java는 SHA-256을 반드시 지원하므로 실제로는 일어나지 않음
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", e);
        }
    }
}
