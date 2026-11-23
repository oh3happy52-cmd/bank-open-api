-- [무엇] API 키 테이블 생성
-- [역할] 앱마다 발급한 API 키를 저장. 키 원문은 저장하지 않고 SHA-256 해시와 화면 표시용 앞 12글자만 저장
-- [누가 쓰나] 서버를 켤 때 Flyway가 한 번 실행. 읽고 쓰는 쪽은 ApiKeyService(발급·목록·폐기)
--            나중에 API 키 인증 필터(/open/v1/**)가 key_hash로 키를 찾는다

CREATE TABLE api_keys (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    app_id      BIGINT        NOT NULL,           -- 어느 앱의 키인가 (apps.id)
    key_prefix  VARCHAR(12)   NOT NULL,           -- 화면 표시용 앞 12글자 (예: boa_live_Xy3). 검색용 아님
    key_hash    VARCHAR(64)   NOT NULL,           -- 키 원문의 SHA-256 해시 (16진수 64글자). 원문은 저장하지 않음
    status      VARCHAR(10)   NOT NULL,           -- ACTIVE / REVOKED
    created_at  DATETIME(6)   NOT NULL,           -- 발급 시각
    revoked_at  DATETIME(6)   NULL,               -- 폐기 시각 (ACTIVE면 NULL. 다시 폐기해도 처음 시각 유지)
    PRIMARY KEY (id),

    -- 요청이 들어오면 "헤더의 키 → SHA-256 → WHERE key_hash = ?"로 한 번에 찾는다 → UNIQUE 인덱스
    UNIQUE KEY uk_api_keys_key_hash (key_hash),

    -- "이 앱의 ACTIVE 키가 몇 개인가"(최대 2개 검사)와 앱별 목록 조회를 빠르게
    KEY idx_api_keys_app_status (app_id, status),

    -- 없는 앱 번호로는 키를 만들 수 없게 DB가 막음
    CONSTRAINT fk_api_keys_app FOREIGN KEY (app_id) REFERENCES apps (id)
);
