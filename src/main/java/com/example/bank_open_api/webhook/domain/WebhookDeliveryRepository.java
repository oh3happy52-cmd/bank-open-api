package com.example.bank_open_api.webhook.domain;

// [무엇] 웹훅 전송 기록 저장소 (webhook_deliveries 테이블)
// [역할] - findDueForUpdate: 보낼 시각이 된 PENDING을 최대 N개, 잠그면서 가져온다 (FOR UPDATE SKIP LOCKED)
//        - existsBy...: 같은 이체의 같은 알림이 이미 있는지
//        - findAllByAppId: 전송 기록 API (페이지)
// [누가 쓰나] WebhookDeliveryStore, WebhookEventRecorder, WebhookEndpointService
//
// ── FOR UPDATE SKIP LOCKED ──
//   FOR UPDATE: 가져온 줄을 이 트랜잭션이 끝날 때까지 잠근다
//   SKIP LOCKED: 다른 일꾼이 이미 잠근 줄은 기다리지 않고 건너뛴다
//   → 서버 여러 대의 일꾼이 동시에 돌아도 같은 알림을 둘이 가져가지 않고, 서로 기다리느라 멈추지도 않는다
//   (MySQL 8.0부터 지원. 인덱스 idx_webhook_deliveries_status_next를 탄다)

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {

    @Query(value = """
            SELECT * FROM webhook_deliveries
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY next_attempt_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<WebhookDelivery> findDueForUpdate(@Param("now") LocalDateTime now, @Param("limit") int limit);

    boolean existsByAppIdAndTransferIdAndEventType(Long appId, Long transferId, String eventType);

    Page<WebhookDelivery> findAllByAppId(Long appId, Pageable pageable);

    List<WebhookDelivery> findAllByAppIdOrderByIdAsc(Long appId);
}
