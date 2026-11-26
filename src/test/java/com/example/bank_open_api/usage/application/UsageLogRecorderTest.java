package com.example.bank_open_api.usage.application;

// [무엇] UsageLogRecorder 단위 테스트 (DB 없이, 저장소는 가짜)
// [역할] - 대기열이 꽉 차면: 넘친 기록은 버리고 WARN(appId만), 요청 스레드는 기다리지 않고 바로 돌아온다
//        - 저장이 실패하면: 그 기록은 버리고 WARN, 예외가 record()를 부른 쪽으로 나가지 않는다
//        - 정상: 다른 스레드(usage-log-*)에서 저장된다
// [누가 쓰나] ./gradlew test (Docker 필요 없음)

import com.example.bank_open_api.usage.domain.ApiUsageLog;
import com.example.bank_open_api.usage.domain.ApiUsageLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class UsageLogRecorderTest {

    ApiUsageLogRepository repository = mock(ApiUsageLogRepository.class);
    UsageLogRecorder recorder;

    @AfterEach
    void shutdown() throws InterruptedException {
        if (recorder != null) {
            recorder.shutdown();
        }
    }

    @Test
    void 정상이면_전용_스레드에서_저장한다() {
        AtomicReference<String> savedOn = new AtomicReference<>();
        when(repository.save(any())).thenAnswer(invocation -> {
            savedOn.set(Thread.currentThread().getName());
            return invocation.getArgument(0);
        });
        recorder = new UsageLogRecorder(repository, new UsageLogProperties(2, 10));

        recorder.record(entry(1L));

        await().atMost(Duration.ofSeconds(2)).until(() -> savedOn.get() != null);
        assertThat(savedOn.get()).startsWith("usage-log-");
        assertThat(recorder.droppedCount()).isZero();
    }

    @Test
    void 대기열이_꽉_차면_버리고_WARN을_남기며_요청_스레드는_기다리지_않는다(CapturedOutput output) throws Exception {
        // 저장 스레드 1개, 대기열 1칸. 저장은 "출발 신호"가 올 때까지 멈춰 있게 함 (= DB가 아주 느린 상황)
        CountDownLatch release = new CountDownLatch(1);
        when(repository.save(any())).thenAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return invocation.getArgument(0);
        });
        recorder = new UsageLogRecorder(repository, new UsageLogProperties(1, 1));

        long started = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            recorder.record(entry(42L));   // 1번: 저장 중(멈춤), 2번: 대기열, 3~10번: 버림
        }
        long tookMillis = (System.nanoTime() - started) / 1_000_000;

        // 저장이 멈춰 있어도 record() 10번이 거의 바로 끝난다 (기다리지 않음)
        assertThat(tookMillis).isLessThan(500);
        // ThreadPoolExecutor는 첫 작업을 대기열을 거치지 않고 새 스레드에 바로 넘긴다
        // → 1번은 스레드가 들고 있고, 2번은 대기열 1칸, 3~10번(8건)은 버림
        assertThat(recorder.droppedCount()).isEqualTo(8);
        assertThat(output.getAll())
                .contains("사용량 기록 버림(대기열 가득 참)")
                .contains("appId=42");

        // 멈춘 저장을 풀어 주면 버리지 않은 기록 2건은 저장된다
        release.countDown();
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> verify(repository, times(2)).save(any(ApiUsageLog.class)));
    }

    @Test
    void 저장이_실패하면_버리고_WARN을_남기며_예외가_밖으로_나가지_않는다(CapturedOutput output) {
        when(repository.save(any())).thenThrow(new DataAccessResourceFailureException("DB 연결 끊김"));
        recorder = new UsageLogRecorder(repository, new UsageLogProperties(1, 10));

        assertThatCode(() -> recorder.record(entry(43L))).doesNotThrowAnyException();

        await().atMost(Duration.ofSeconds(2)).until(() -> recorder.droppedCount() == 1);
        assertThat(output.getAll())
                .contains("사용량 기록 버림(저장 실패)")
                .contains("appId=43")
                .doesNotContain("DB 연결 끊김");   // 예외 메시지(접속 주소 등이 들어 있을 수 있음)는 남기지 않음
    }

    private static UsageLogEntry entry(Long appId) {
        return new UsageLogEntry(appId, 1L, "GET", "/open/v1/ping", 200, 3,
                LocalDateTime.of(2026, 11, 26, 10, 0));
    }
}
