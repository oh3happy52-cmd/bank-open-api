package com.example.bank_open_api.common.config;

// [무엇] 현재 시각을 알려 주는 시계(Clock) 빈
// [역할] "지금 몇 시인가"를 LocalDateTime.now() 대신 이 시계에게 묻게 한다
//        - 시간대를 Asia/Seoul로 고정 → 서버 컴퓨터가 UTC여도 한국 시간으로 기록·계산
//        - 테스트에서는 이 시계를 "멈춘 시계"로 바꿔 끼워 날짜 경계(자정 등)를 정확히 시험할 수 있다
// [누가 쓰나] UsageLogFilter(요청 시각 기록), UsageStatsService(기간 생략 시 "오늘" 계산)
//            (1주차 코드의 LocalDateTime.now()는 이번 범위가 아니라 그대로 둠)

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class ClockConfig {

    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(SEOUL);
    }
}
