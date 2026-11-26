package com.example.bank_open_api.webhook.application;

// [무엇] 웹훅 발송 일꾼
// [역할] dispatchDue() 한 번 = 보낼 시각이 된 알림을 최대 20개 꺼내(1단계) 하나씩 보내고(2단계) 결과를 기록(3단계)
//        - 웹훅이 꺼져 있거나 설정이 없음 → CANCELED (보내지 않음)
//        - 보내는 시점의 "현재" 비밀키로 서명 (비밀키를 교체했으면 재전송도 새 키로)
//        - 2xx → SUCCEEDED / 그 밖 → 2, 4, 8, 16, 32초 뒤 재시도, 6번째 실패 → FAILED
// [누가 쓰나] WebhookDispatchScheduler(1초마다), 테스트(직접 호출)
//
// 3단계로 나눈 이유와 서버 재시작 때 이어서 보내는 원리는 WebhookDeliveryStore 주석 참고.
// 한 번에 하나씩 순서대로 보낸다 (단순함 우선. 받는 쪽이 많이 느리면 한 바퀴가 길어질 수 있음)
//
// ── 로그 규칙 ── appId, eventId, deliveryId, 시도 횟수, 결과(상태 코드·TIMEOUT 등)만.
//                비밀키·서명값·본문·받는 주소 전체는 남기지 않는다

import com.example.bank_open_api.webhook.crypto.WebhookSecretCipher;
import com.example.bank_open_api.webhook.crypto.WebhookSigner;
import com.example.bank_open_api.webhook.domain.DeliveryStatus;
import com.example.bank_open_api.webhook.domain.WebhookEndpoint;
import com.example.bank_open_api.webhook.domain.WebhookEndpointRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class WebhookDispatcher {

    private final WebhookDeliveryStore store;
    private final WebhookEndpointRepository endpointRepository;
    private final WebhookSecretCipher secretCipher;
    private final WebhookSender sender;
    private final Clock clock;

    public WebhookDispatcher(WebhookDeliveryStore store, WebhookEndpointRepository endpointRepository,
                             WebhookSecretCipher secretCipher, WebhookSender sender, Clock clock) {
        this.store = store;
        this.endpointRepository = endpointRepository;
        this.secretCipher = secretCipher;
        this.sender = sender;
        this.clock = clock;
    }

    /**
     * 한 바퀴 실행. 절대 예외를 밖으로 던지지 않는다 (스케줄러가 멈추지 않게)
     *
     * @return 이번에 처리(성공·실패·취소)한 알림 수
     */
    public int dispatchDue() {
        List<ClaimedDelivery> claimed;
        try {
            claimed = store.claimDue(LocalDateTime.now(clock));   // 1단계 (짧은 트랜잭션)
        } catch (RuntimeException e) {
            log.error("웹훅 알림 꺼내기 실패: error={}", e.getClass().getSimpleName());
            return 0;
        }
        for (ClaimedDelivery delivery : claimed) {
            try {
                dispatchOne(delivery);
            } catch (RuntimeException e) {
                // 한 건이 이상해도 나머지는 계속. 이 건은 lease가 지나면 다시 꺼내진다
                log.error("웹훅 발송 중 오류: deliveryId={}, appId={}, error={}",
                        delivery.id(), delivery.appId(), e.getClass().getSimpleName());
            }
        }
        return claimed.size();
    }

    private void dispatchOne(ClaimedDelivery delivery) {
        Optional<WebhookEndpoint> endpoint = endpointRepository.findByAppId(delivery.appId());
        if (endpoint.isEmpty() || !endpoint.get().isEnabled()) {
            store.markCanceled(delivery.id(), "WEBHOOK_DISABLED");
            log.info("웹훅 취소(꺼져 있음): appId={}, eventId={}", delivery.appId(), delivery.eventId());
            return;
        }

        String secret;
        try {
            secret = secretCipher.decrypt(endpoint.get().getSecretCiphertext(), delivery.appId());
        } catch (IllegalStateException e) {
            // 암호화 키가 바뀌었거나 암호문이 손상됨 → 보낼 수 없음. 실패로 기록해 재시도·FAILED 흐름을 탄다
            log.error("웹훅 비밀키 복호화 실패: appId={}, eventId={}", delivery.appId(), delivery.eventId());
            recordFailure(delivery, null, "SECRET_UNAVAILABLE");
            return;
        }

        // 2단계 (트랜잭션 없음): 서명하고 보내기
        long timestamp = clock.instant().getEpochSecond();
        byte[] body = delivery.payload().getBytes(StandardCharsets.UTF_8);
        String signature = WebhookSigner.sign(secret, timestamp, body);
        WebhookSender.SendOutcome outcome =
                sender.send(endpoint.get().getUrl(), delivery.eventId(), timestamp, signature, body);

        // 3단계 (짧은 트랜잭션): 결과 기록
        if (outcome.success()) {
            store.markSucceeded(delivery.id(), outcome.statusCode(), LocalDateTime.now(clock));
            log.info("웹훅 전송 성공: appId={}, eventId={}, attempt={}, status={}",
                    delivery.appId(), delivery.eventId(), delivery.attemptCount() + 1, outcome.statusCode());
        } else {
            recordFailure(delivery, outcome.statusCode(), outcome.error());
        }
    }

    private void recordFailure(ClaimedDelivery delivery, Integer statusCode, String error) {
        Optional<DeliveryStatus> after = store.markFailed(delivery.id(), statusCode, error, LocalDateTime.now(clock));
        log.warn("웹훅 전송 실패: appId={}, eventId={}, attempt={}, result={}, next={}",
                delivery.appId(), delivery.eventId(), delivery.attemptCount() + 1, error,
                after.map(status -> status == DeliveryStatus.FAILED ? "FAILED(더 보내지 않음)" : "재시도 예정").orElse("-"));
    }
}
