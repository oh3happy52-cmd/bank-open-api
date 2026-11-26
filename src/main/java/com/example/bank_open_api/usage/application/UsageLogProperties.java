package com.example.bank_open_api.usage.application;

// [무엇] 사용량 기록 저장 스레드풀 설정값
// [역할] application.properties의 usage-log.* 값을 받는다
//          usage-log.pool-size=2          저장을 맡는 스레드 수
//          usage-log.queue-capacity=1000  저장을 기다릴 수 있는 기록 수 (이보다 많이 밀리면 새 기록은 버림)
// [누가 쓰나] UsageLogRecorder

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "usage-log")
public record UsageLogProperties(int poolSize, int queueCapacity) {

    public UsageLogProperties {
        if (poolSize < 1) {
            throw new IllegalArgumentException("usage-log.pool-size는 1 이상이어야 합니다: " + poolSize);
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("usage-log.queue-capacity는 1 이상이어야 합니다: " + queueCapacity);
        }
    }
}
