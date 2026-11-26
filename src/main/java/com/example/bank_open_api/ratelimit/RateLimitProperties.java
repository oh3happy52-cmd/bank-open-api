package com.example.bank_open_api.ratelimit;

// [무엇] 호출 제한(토큰 버킷) 설정값 묶음
// [역할] application.properties의 rate-limit.* 값을 한 객체로 받아 둔다
//          rate-limit.capacity=10           양동이 크기 = 한 번에 몰아서 보낼 수 있는 최대 요청 수
//          rate-limit.refill-per-second=1   1초에 다시 채워지는 토큰 수 (소수 가능)
//        값이 잘못되면(0 이하) 서버가 시작할 때 바로 멈춰서 알려 준다
// [누가 쓰나] RateLimiter(스크립트에 넘길 값, 응답 헤더 X-RateLimit-Limit)

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rate-limit")
public record RateLimitProperties(
        int capacity,             // 양동이 크기 (1 이상)
        double refillPerSecond    // 초당 채우는 토큰 수 (0보다 커야 함)
) {

    public RateLimitProperties {
        if (capacity < 1) {
            throw new IllegalArgumentException("rate-limit.capacity는 1 이상이어야 합니다: " + capacity);
        }
        if (!(refillPerSecond > 0)) {   // NaN도 걸러지도록 "> 0이 아니면"으로 검사
            throw new IllegalArgumentException("rate-limit.refill-per-second는 0보다 커야 합니다: " + refillPerSecond);
        }
    }

    /**
     * Redis 키 유효 시간(밀리초) = 빈 양동이가 가득 차는 시간의 2배 (최소 1초)
     * 이 시간 동안 요청이 없던 앱은 어차피 양동이가 가득 찼을 것이므로, 키를 지워도(= 가득 찬 새 양동이) 결과가 같다.
     * 예) capacity 10, 초당 1개 → 가득 차는 데 10초 → TTL 20초
     */
    public long ttlMillis() {
        double fullRefillMillis = capacity / refillPerSecond * 1000;
        return Math.max(1000L, (long) Math.ceil(fullRefillMillis * 2));
    }
}
