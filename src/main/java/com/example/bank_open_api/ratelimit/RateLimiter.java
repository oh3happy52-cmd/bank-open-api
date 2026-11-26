package com.example.bank_open_api.ratelimit;

// [무엇] 앱별 호출 제한 계산기 (Redis 토큰 버킷)
// [역할] 앱 번호(appId)를 받아 Redis의 양동이(ratelimit:app:{appId})에서 토큰 1개를 꺼내 본다
//        실제 계산(읽기 → 채우기 → 꺼내기 → 쓰기)은 token_bucket.lua가 Redis 안에서 한 번에 한다
//        - 성공: RateLimitResult (통과/거절, 남은 토큰, 다음 토큰까지 남은 시간)
//        - Redis 장애(연결 실패, 0.5초 제한 시간 초과 등): Optional.empty() → 필터가 그냥 통과시킨다 (fail-open)
// [누가 쓰나] RateLimitFilter (/open/v1/** 요청마다 한 번)
//
// ── 왜 양동이 기준이 "키"가 아니라 "앱"인가? ──
//   앱당 키를 2개까지 쓸 수 있다(키 교체 기간용). 키마다 양동이를 주면 키를 2개 만들어 한도를 2배로 쓸 수 있다.
//   그래서 같은 앱의 키는 양동이 1개를 함께 쓴다.
//
// ── 왜 Redis 장애 때 막지 않고 통과(fail-open)시키나? ──
//   호출 제한은 "과하게 쓰는 것을 줄이는" 보조 장치이고, 인증(API 키 검사)은 이미 끝났다.
//   Redis가 잠깐 죽었다고 모든 핀테크 앱의 계좌 조회가 막히면 피해가 더 크다.
//   대신 WARN 로그를 남겨 운영자가 알 수 있게 한다.
//
// ── 로그 규칙 ──
//   appId와 예외 종류만 남긴다. API 키 원문·해시는 이 클래스로 들어오지도 않는다.

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class RateLimiter {

    // Redis 키 앞부분. 최종 모양: ratelimit:app:42
    static final String KEY_PREFIX = "ratelimit:app:";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List<Long>> tokenBucketScript;
    private final RateLimitProperties properties;

    public RateLimiter(StringRedisTemplate redisTemplate,
                       RedisScript<List<Long>> tokenBucketScript,
                       RateLimitProperties properties) {
        this.redisTemplate = redisTemplate;
        this.tokenBucketScript = tokenBucketScript;
        this.properties = properties;
    }

    public static String keyOf(Long appId) {
        return KEY_PREFIX + appId;
    }

    /**
     * 토큰 1개 꺼내기 시도
     *
     * @param appId 요청한 앱 번호 (API 키 인증이 끝난 ApiClient에서 꺼낸 값)
     * @return 검사 결과. Redis를 쓸 수 없으면 empty (= 제한 없이 통과)
     */
    public Optional<RateLimitResult> tryConsume(Long appId) {
        List<Long> reply;
        try {
            // StringRedisTemplate이라 인자는 전부 문자열로 보낸다. Lua에서 tonumber로 숫자로 바꾼다
            reply = redisTemplate.execute(
                    tokenBucketScript,
                    List.of(keyOf(appId)),
                    String.valueOf(properties.capacity()),
                    String.valueOf(properties.refillPerSecond()),
                    String.valueOf(properties.ttlMillis()));
        } catch (DataAccessException e) {
            // RedisConnectionFailureException(연결 실패), QueryTimeoutException(제한 시간 초과),
            // RedisSystemException(스크립트 오류 등)이 모두 DataAccessException의 자식이다
            log.warn("호출 제한 검사 건너뜀(Redis 사용 불가, fail-open): appId={}, error={}",
                    appId, e.getClass().getSimpleName());
            return Optional.empty();
        }

        if (reply == null || reply.size() < 3) {
            // 정상이라면 일어나지 않음. 그래도 이상한 응답 때문에 요청을 막지는 않는다
            log.warn("호출 제한 검사 건너뜀(스크립트 응답이 올바르지 않음, fail-open): appId={}", appId);
            return Optional.empty();
        }

        return Optional.of(new RateLimitResult(
                reply.get(0) == 1L,
                properties.capacity(),
                reply.get(1),
                reply.get(2)));
    }
}
