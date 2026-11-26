-- [무엇] 웹훅 설정 테이블 생성
-- [역할] 앱마다 "이체 완료 알림을 받을 주소(url)"와 서명 비밀키를 1개씩 저장
--        비밀키는 서명할 때 다시 꺼내 써야 하므로 해시가 아니라 AES-256-GCM 암호문으로 저장한다 (평문 없음)
-- [누가 쓰나] 서버를 켤 때 Flyway가 한 번 실행. 읽고 쓰는 쪽은 WebhookEndpointService(설정 API),
--            WebhookEventRecorder(웹훅이 켜져 있는지 확인), WebhookDispatcher(보낼 주소·비밀키)

CREATE TABLE webhook_endpoints (
    id                 BIGINT         NOT NULL AUTO_INCREMENT,
    app_id             BIGINT         NOT NULL,          -- 어느 앱의 설정인가 (apps.id). 앱당 1개
    url                VARCHAR(2048)  NOT NULL,          -- 알림을 받을 주소 (http/https)
    secret_ciphertext  VARCHAR(255)   NOT NULL,          -- 서명 비밀키 암호문 "v1:" + Base64(IV 12바이트 + 암호문 + 인증 태그)
    secret_prefix      VARCHAR(12)    NOT NULL,          -- 화면 표시용 앞 12글자 (예: whsec_AbC123). 혼자서는 쓸 수 없음
    enabled            BOOLEAN        NOT NULL,          -- 켜짐/꺼짐 (DELETE API가 끔. 끄면 새 알림을 만들지 않고 남은 알림은 CANCELED)
    created_at         DATETIME(6)    NOT NULL,
    updated_at         DATETIME(6)    NOT NULL,          -- url·켜짐 상태가 마지막으로 바뀐 시각
    secret_rotated_at  DATETIME(6)    NOT NULL,          -- 비밀키를 마지막으로 발급한 시각 (처음 등록 포함)
    PRIMARY KEY (id),

    -- 앱당 설정 1개 (WHERE app_id = ? 로 바로 찾음)
    UNIQUE KEY uk_webhook_endpoints_app (app_id),

    CONSTRAINT fk_webhook_endpoints_app FOREIGN KEY (app_id) REFERENCES apps (id)
);
