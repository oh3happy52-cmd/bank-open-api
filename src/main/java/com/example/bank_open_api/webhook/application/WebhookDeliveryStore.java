package com.example.bank_open_api.webhook.application;

// [무엇] 웹훅 전송 기록의 DB 작업 모음 (짧은 트랜잭션들)
// [역할] ① claimDue: 보낼 시각이 된 PENDING을 잠그며 꺼내고(SKIP LOCKED), "맡아 둠" 표시(lease) 후 바로 커밋
//        ② markSucceeded / markFailed / markCanceled: 보낸 결과를 기록 (각각 별도 짧은 트랜잭션)
// [누가 쓰나] WebhookDispatcher
//
// ── HTTP를 보내는 동안 DB 잠금을 붙잡지 않는 방법 (★) ──
//   한 트랜잭션 안에서 "꺼내기 → HTTP 보내기 → 결과 기록"을 하면, 받는 쪽이 느릴 때(최대 5초 × 20개)
//   그동안 DB 연결과 줄 잠금을 계속 쥐고 있게 된다 → DB 연결이 바닥나고 다른 요청이 막힌다.
//   그래서 세 단계로 나눈다:
//     1단계 [트랜잭션] claimDue: SELECT ... FOR UPDATE SKIP LOCKED로 잠근 줄의 next_attempt_at을
//                     "지금 + lease(2분)"로 바꾸고 커밋 → 잠금은 여기서 풀린다.
//                     상태는 PENDING 그대로지만 시각이 미래라 다른 일꾼의 조회(next_attempt_at <= 지금)에 안 걸린다.
//     2단계 [트랜잭션 없음] WebhookDispatcher가 HTTP를 보낸다 (DB 연결도, 잠금도 쥐고 있지 않음)
//     3단계 [트랜잭션] mark...: 결과를 기록 (성공 / 다음 시각으로 미룸 / FAILED / CANCELED)
//   일꾼이 2단계에서 죽으면(서버 재시작) 3단계가 없으니 줄은 PENDING + lease 시각으로 남는다
//   → lease가 지나면 다른(또는 재시작한) 일꾼이 다시 꺼내 보낸다. 알림이 사라지지 않는다.

import com.example.bank_open_api.webhook.config.WebhookProperties;
import com.example.bank_open_api.webhook.domain.DeliveryStatus;
import com.example.bank_open_api.webhook.domain.WebhookDelivery;
import com.example.bank_open_api.webhook.domain.WebhookDeliveryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class WebhookDeliveryStore {

    private final WebhookDeliveryRepository repository;
    private final WebhookProperties properties;

    public WebhookDeliveryStore(WebhookDeliveryRepository repository, WebhookProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    // 1단계: 꺼내서 맡아 두기 (트랜잭션이 끝나면 잠금이 풀린다)
    @Transactional
    public List<ClaimedDelivery> claimDue(LocalDateTime now) {
        List<WebhookDelivery> due = repository.findDueForUpdate(now, properties.dispatch().batchSize());
        LocalDateTime leaseUntil = now.plus(properties.dispatch().lease());
        due.forEach(delivery -> delivery.lease(leaseUntil));   // 커밋 때 UPDATE로 반영 (JPA 변경 감지)
        return due.stream().map(ClaimedDelivery::from).toList();
    }

    // 3단계: 성공
    @Transactional
    public void markSucceeded(Long id, int statusCode, LocalDateTime now) {
        pending(id).ifPresent(delivery -> delivery.succeed(statusCode, now));
    }

    /**
     * 3단계: 실패. 다음 시각 = 지금 + base × 2^(시도 횟수 - 1)  → 2, 4, 8, 16, 32초
     * 시도 횟수가 max-attempts(6)에 닿으면 FAILED
     *
     * @return 기록 후 상태 (PENDING 또는 FAILED). 이미 처리된 줄이면 empty
     */
    @Transactional
    public Optional<DeliveryStatus> markFailed(Long id, Integer statusCode, String error, LocalDateTime now) {
        return pending(id).map(delivery -> {
            int attemptsAfter = delivery.getAttemptCount() + 1;
            boolean exhausted = attemptsAfter >= properties.retry().maxAttempts();
            Duration backoff = properties.retry().base().multipliedBy(1L << Math.min(attemptsAfter - 1, 30));
            delivery.fail(statusCode, error, now.plus(backoff), exhausted);
            return delivery.getStatus();
        });
    }

    // 3단계: 보낼 때 웹훅이 꺼져 있음
    @Transactional
    public void markCanceled(Long id, String reason) {
        pending(id).ifPresent(delivery -> delivery.cancel(reason));
    }

    // 아직 PENDING인 줄만 고친다 (혹시 다른 일꾼이 먼저 결과를 기록했으면 건드리지 않음)
    private Optional<WebhookDelivery> pending(Long id) {
        Optional<WebhookDelivery> found = repository.findById(id).filter(d -> d.getStatus() == DeliveryStatus.PENDING);
        if (found.isEmpty()) {
            log.warn("웹훅 결과 기록 건너뜀(이미 처리됨): deliveryId={}", id);
        }
        return found;
    }
}
