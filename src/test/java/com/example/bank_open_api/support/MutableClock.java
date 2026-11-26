package com.example.bank_open_api.support;

// [무엇] 테스트용 "직접 맞추는 시계"
// [역할] 진짜 시간 대신 테스트가 정한 시각을 돌려준다. set()으로 언제든 바꿀 수 있다 (시간대는 Asia/Seoul)
//        → "2026-11-26 23:59:59.999에 들어온 요청" 같은 날짜 경계를 정확히 만들 수 있다
// [누가 쓰나] TestClockConfiguration(빈 등록), UsageLogTest, UsageStatsTest

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

public class MutableClock extends Clock {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    // 여러 스레드(요청 스레드, 테스트 스레드)가 읽으므로 AtomicReference
    private final AtomicReference<Instant> now;

    public MutableClock(LocalDateTime seoulTime) {
        this.now = new AtomicReference<>(seoulTime.atZone(SEOUL).toInstant());
    }

    // 한국 시간으로 시각 맞추기
    public void set(LocalDateTime seoulTime) {
        now.set(seoulTime.atZone(SEOUL).toInstant());
    }

    @Override
    public ZoneId getZone() {
        return SEOUL;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("테스트 시계는 Asia/Seoul로만 쓴다");
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
