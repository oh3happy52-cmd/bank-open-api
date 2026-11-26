package com.example.bank_open_api.support;

// [무엇] 테스트에서 진짜 시계(ClockConfig) 대신 MutableClock을 쓰게 하는 설정
// [역할] @Primary로 등록해서, Clock을 받는 곳(UsageLogFilter, UsageStatsService)이 이 시계를 받게 한다
//        처음 시각은 2026-11-26 12:00 (한국 시간). 테스트가 set()으로 바꿀 수 있다
// [누가 쓰나] @Import(TestClockConfiguration.class)를 붙인 테스트 (UsageLogTest, UsageStatsTest)

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.LocalDateTime;

@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfiguration {

    public static final LocalDateTime INITIAL_TIME = LocalDateTime.of(2026, 11, 26, 12, 0);

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock(INITIAL_TIME);
    }
}
