package com.example.bank_open_api.ratelimit;

// [무엇] 호출 제한 검사 결과 한 건
// [역할] Lua 스크립트가 돌려준 값(통과 여부, 남은 토큰, 다음 토큰까지 남은 시간)을 담고,
//        응답 헤더에 쓸 값(Retry-After 초)으로 바꿔 준다
// [누가 쓰나] RateLimiter(만듦), RateLimitFilter(헤더 쓰기·통과/거절 판단)

public record RateLimitResult(
        boolean allowed,      // true = 통과, false = 429
        int limit,            // 양동이 크기 → X-RateLimit-Limit
        long remaining,       // 남은 토큰(정수) → X-RateLimit-Remaining
        long retryAfterMillis // 다음 토큰 1개가 생길 때까지 남은 시간(밀리초). 토큰이 남아 있으면 0
) {

    /**
     * Retry-After 헤더 값(초). 올림하고 최소 1초
     * 예) 0.2초 남음 → 1, 1.5초 남음 → 2
     * (0을 주면 클라이언트가 "바로 다시 보내도 된다"로 읽어 곧바로 또 429를 받으므로 최소 1)
     */
    public long retryAfterSeconds() {
        long seconds = (retryAfterMillis + 999) / 1000;   // 정수 올림
        return Math.max(1, seconds);
    }
}
