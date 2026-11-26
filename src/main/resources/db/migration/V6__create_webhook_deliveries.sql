-- [무엇] 웹훅 전송 기록 테이블 생성 (아웃박스)
-- [역할] 보낼 알림 1건 = 1줄. 이체 성공 때 PENDING으로 저장하고, 발송 일꾼이 꺼내 보내며 상태를 바꾼다
--          PENDING   : 보낼 차례를 기다림 (next_attempt_at이 되면 보냄. 실패하면 시각을 뒤로 미뤄 다시 PENDING)
--          SUCCEEDED : 받는 쪽이 2xx로 응답함
--          FAILED    : 최대 시도 횟수(6번)를 모두 실패함
--          CANCELED  : 보낼 때 웹훅이 꺼져 있었음
-- [누가 쓰나] 서버를 켤 때 Flyway가 한 번 실행. 쓰는 쪽은 WebhookEventRecorder(저장), WebhookDeliveryStore(꺼내기·결과 기록),
--            읽는 쪽은 WebhookEndpointService(전송 기록 API)
--
-- payload는 알림을 만든 순간의 내용을 그대로 저장한다(스냅샷). 재전송해도 eventId·내용이 처음과 같다.
-- 주의: payload에는 이체 계좌번호 전체가 들어 있다(받는 앱이 필요로 함). 이 표를 로그·화면에 그대로 내보내지 않는다.

CREATE TABLE webhook_deliveries (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    app_id            BIGINT        NOT NULL,          -- 받는 앱 (apps.id)
    event_id          VARCHAR(40)   NOT NULL,          -- "evt_" + UUID. 받는 쪽이 중복 알림을 걸러 내는 기준
    event_type        VARCHAR(50)   NOT NULL,          -- transfer.completed
    transfer_id       BIGINT        NOT NULL,          -- bank_core 이체 번호
    payload           JSON          NOT NULL,          -- 보낼 본문 (만들 때 확정)
    status            VARCHAR(10)   NOT NULL,          -- PENDING / SUCCEEDED / FAILED / CANCELED
    attempt_count     INT           NOT NULL,          -- 보낸 횟수 (성공·실패 모두 셈)
    next_attempt_at   DATETIME(3)   NOT NULL,          -- 다음에 보낼 시각 (PENDING일 때만 의미 있음)
    last_status_code  SMALLINT      NULL,              -- 마지막 시도에서 받은 HTTP 상태 코드 (타임아웃·연결 실패면 NULL)
    last_error        VARCHAR(200)  NULL,              -- 마지막 실패 이유 짧게 (예: HTTP 500, TIMEOUT). 본문·비밀값은 넣지 않음
    created_at        DATETIME(3)   NOT NULL,
    delivered_at      DATETIME(3)   NULL,              -- 성공한 시각
    PRIMARY KEY (id),

    UNIQUE KEY uk_webhook_deliveries_event (event_id),
    -- 같은 이체의 같은 알림은 1줄만: 같은 Idempotency-Key 재요청으로 이체 성공이 또 와도 중복 저장되지 않음
    UNIQUE KEY uk_webhook_deliveries_app_transfer_type (app_id, transfer_id, event_type),
    -- 발송 일꾼: WHERE status = 'PENDING' AND next_attempt_at <= ? ORDER BY next_attempt_at
    KEY idx_webhook_deliveries_status_next (status, next_attempt_at),
    -- 전송 기록 API: WHERE app_id = ? ORDER BY created_at DESC
    KEY idx_webhook_deliveries_app_created (app_id, created_at),

    CONSTRAINT fk_webhook_deliveries_app FOREIGN KEY (app_id) REFERENCES apps (id)
);
