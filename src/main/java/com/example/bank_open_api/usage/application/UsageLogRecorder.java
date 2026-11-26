package com.example.bank_open_api.usage.application;

// [무엇] 사용량 기록 비동기 저장기
// [역할] 요청 스레드에게서 기록(UsageLogEntry)을 받아 "대기열"에 넣기만 하고 바로 돌아간다.
//        실제 DB 저장은 전용 스레드(usage-log-1, usage-log-2)가 대기열에서 꺼내 한다.
//        → API 응답은 DB 저장을 기다리지 않는다
// [누가 쓰나] UsageLogFilter (/open/v1/** 요청이 끝날 때마다 record 호출)
//
// ── 전용 스레드풀 (ThreadPoolExecutor) ──
//   스레드 수 고정(usage-log.pool-size), 대기열 크기 제한(usage-log.queue-capacity, ArrayBlockingQueue)
//   - 대기열 크기를 제한하는 이유: DB가 느려져 기록이 끝없이 쌓이면 메모리가 바닥나 서버 전체가 죽는다
//   - Spring Boot의 기본 작업 스레드풀(applicationTaskExecutor)은 쓰지 않는다:
//     다른 비동기 작업과 섞이지 않게, 그리고 Executor 빈을 새로 등록하면 Boot 기본 풀 설정이 꺼지므로
//     빈으로 등록하지 않고 이 클래스 안에서 직접 만든다
//
// ── 대기열이 꽉 차거나 저장이 실패하면? ──
//   그 기록은 버리고 WARN 로그(appId만)를 남긴다. API 응답에는 아무 영향이 없다.
//   - 꽉 참: execute()가 기다리지 않고 바로 RejectedExecutionException을 던진다(AbortPolicy)
//            → 요청 스레드가 대기열 자리가 날 때까지 멈추는 일이 없다
//            (CallerRunsPolicy를 쓰면 요청 스레드가 직접 저장하게 되어 응답이 느려지므로 쓰지 않음)
//   - 저장 실패: 저장 스레드 안에서 예외를 잡아 로그만 남긴다
//   사용량 기록은 "통계용"이라 몇 건 빠져도 되지만, API가 느려지거나 실패하면 안 된다는 판단.
//   (나중에 과금 근거로 쓰려면 버리는 대신 다른 저장 방식이 필요하다)
//
// ── 서버를 끌 때 ──
//   @PreDestroy: 새 기록은 더 받지 않고, 대기열에 남은 기록은 최대 5초 동안 마저 저장한다

import com.example.bank_open_api.usage.domain.ApiUsageLogRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class UsageLogRecorder {

    private static final long SHUTDOWN_WAIT_SECONDS = 5;

    private final ApiUsageLogRepository repository;
    private final ExecutorService executor;

    // 버린 기록 수 (운영 확인·테스트용)
    private final AtomicLong droppedCount = new AtomicLong();

    @Autowired
    public UsageLogRecorder(ApiUsageLogRepository repository, UsageLogProperties properties) {
        this(repository, newExecutor(properties));
    }

    // 테스트에서 작은 스레드풀을 직접 넣을 때 사용
    UsageLogRecorder(ApiUsageLogRepository repository, ExecutorService executor) {
        this.repository = repository;
        this.executor = executor;
    }

    static ThreadPoolExecutor newExecutor(UsageLogProperties properties) {
        AtomicInteger threadNumber = new AtomicInteger(1);
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "usage-log-" + threadNumber.getAndIncrement());
            thread.setDaemon(true);   // 혹시 종료 처리가 안 돼도 이 스레드 때문에 프로그램이 안 꺼지는 일이 없게
            return thread;
        };
        return new ThreadPoolExecutor(
                properties.poolSize(), properties.poolSize(),   // 스레드 수 고정
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.queueCapacity()),   // 크기 제한 대기열
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());   // 꽉 차면 기다리지 않고 바로 거절
    }

    /**
     * 기록 한 건을 대기열에 넣는다. 절대 예외를 밖으로 던지지 않고, 절대 기다리지 않는다
     */
    public void record(UsageLogEntry entry) {
        try {
            executor.execute(() -> save(entry));
        } catch (RejectedExecutionException e) {
            // 대기열이 꽉 찼거나 서버가 꺼지는 중
            droppedCount.incrementAndGet();
            log.warn("사용량 기록 버림(대기열 가득 참): appId={}", entry.appId());
        }
    }

    // 저장 스레드에서 실행
    private void save(UsageLogEntry entry) {
        try {
            repository.save(entry.toEntity());
        } catch (RuntimeException e) {
            droppedCount.incrementAndGet();
            log.warn("사용량 기록 버림(저장 실패): appId={}, error={}", entry.appId(), e.getClass().getSimpleName());
        }
    }

    public long droppedCount() {
        return droppedCount.get();
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
            int left = executor.shutdownNow().size();
            log.warn("서버 종료: 저장하지 못한 사용량 기록 {}건을 버림", left);
        }
    }
}
