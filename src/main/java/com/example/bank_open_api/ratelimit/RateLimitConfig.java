package com.example.bank_open_api.ratelimit;

// [무엇] 호출 제한 설정 등록
// [역할] ① rate-limit.* 설정값(RateLimitProperties)을 쓸 수 있게 켠다
//        ② src/main/resources/ratelimit/token_bucket.lua 를 읽어 RedisScript 빈으로 만든다
//           (DefaultRedisScript는 스크립트의 SHA1을 미리 계산해 두고 EVALSHA로 보낸다.
//            Redis에 아직 없으면 자동으로 EVAL로 스크립트 전체를 보내 등록한다 → 매번 긴 스크립트를 보내지 않음)
// [누가 쓰나] Spring이 서버 시작 때 읽음. 만든 빈은 RateLimiter가 받아 씀

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    @Bean
    @SuppressWarnings({"unchecked", "rawtypes"})
    public RedisScript<List<Long>> tokenBucketScript() {
        // 스크립트는 정수 3개짜리 배열을 돌려준다 → Java에서는 List<Long>
        DefaultRedisScript script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("ratelimit/token_bucket.lua"));
        script.setResultType(List.class);
        return (RedisScript<List<Long>>) script;
    }
}
