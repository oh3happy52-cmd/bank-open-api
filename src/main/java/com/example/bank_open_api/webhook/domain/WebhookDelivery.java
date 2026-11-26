package com.example.bank_open_api.webhook.domain;

// [무엇] 웹훅 전송 기록 엔티티 (DB의 webhook_deliveries 테이블 한 줄 = 보낼 알림 1건)
// [역할] 알림 내용(payload, 만들 때 확정)과 전송 상태·시도 횟수·다음 시도 시각을 들고 있다
//        상태 바꾸기는 이 클래스의 메서드로만 한다 (lease, succeed, fail, cancel)
// [누가 쓰나] WebhookEventRecorder(만듦), WebhookDeliveryStore(꺼내기·결과 기록), WebhookEndpointService(목록)

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "webhook_deliveries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookDelivery {

    // last_error 열 길이
    public static final int LAST_ERROR_MAX = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_id", nullable = false)
    private Long appId;

    @Column(name = "event_id", nullable = false, unique = true, length = 40)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "transfer_id", nullable = false)
    private Long transferId;

    // MySQL JSON 열 ↔ 문자열. MySQL은 JSON을 저장할 때 칸 순서·공백을 자기 방식으로 정리하므로,
    // 발송 일꾼은 "DB에서 읽은 이 문자열"을 그대로 서명하고 그대로 보낸다 (서명한 바이트 = 보낸 바이트)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 10)
    private DeliveryStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_status_code")
    private Short lastStatusCode;

    @Column(name = "last_error", length = LAST_ERROR_MAX)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    // 새 알림: 바로 보낼 수 있게 next_attempt_at = 지금
    public static WebhookDelivery pending(Long appId, String eventId, String eventType, Long transferId,
                                          String payload, LocalDateTime now) {
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.appId = appId;
        delivery.eventId = eventId;
        delivery.eventType = eventType;
        delivery.transferId = transferId;
        delivery.payload = payload;
        delivery.status = DeliveryStatus.PENDING;
        delivery.attemptCount = 0;
        delivery.nextAttemptAt = now;
        delivery.createdAt = now;
        return delivery;
    }

    // 일꾼이 꺼냄: 상태는 PENDING 그대로, 다음 시각만 leaseUntil로 미뤄 다른 일꾼이 꺼내지 않게 "맡아 둠"
    // (일꾼이 보내다 죽으면 leaseUntil이 지난 뒤 다른 일꾼이 다시 꺼낸다 → 재시작해도 알림이 사라지지 않음)
    public void lease(LocalDateTime leaseUntil) {
        this.nextAttemptAt = leaseUntil;
    }

    public void succeed(int statusCode, LocalDateTime now) {
        this.attemptCount++;
        this.status = DeliveryStatus.SUCCEEDED;
        this.lastStatusCode = (short) statusCode;
        this.lastError = null;
        this.deliveredAt = now;
    }

    /**
     * 실패 기록. 횟수가 남았으면 PENDING + 다음 시각, 다 썼으면 FAILED
     *
     * @param statusCode 받은 상태 코드 (타임아웃·연결 실패면 null)
     * @param error      짧은 이유 (본문·비밀값 금지). 200자를 넘으면 자름
     */
    public void fail(Integer statusCode, String error, LocalDateTime nextAttemptAt, boolean exhausted) {
        this.attemptCount++;
        this.lastStatusCode = statusCode == null ? null : statusCode.shortValue();
        this.lastError = error == null ? null
                : (error.length() > LAST_ERROR_MAX ? error.substring(0, LAST_ERROR_MAX) : error);
        if (exhausted) {
            this.status = DeliveryStatus.FAILED;
        } else {
            this.nextAttemptAt = nextAttemptAt;
        }
    }

    public void cancel(String reason) {
        this.status = DeliveryStatus.CANCELED;
        this.lastError = reason;
    }
}
