-- [무엇] API 사용량 기록 테이블 생성
-- [역할] API 키 인증을 통과한 /open/v1/** 요청을 한 건에 한 줄씩 기록 (결과가 200이든 429·502든 전부)
--        개발자 포털의 일별 통계(GET /api/v1/apps/{appId}/usage)가 이 표를 모아서 계산한다
-- [누가 쓰나] 서버를 켤 때 Flyway가 한 번 실행. 쓰는 쪽은 UsageLogRecorder(비동기 저장), 읽는 쪽은 UsageStatsService
--
-- 주의: endpoint에는 실제 주소가 아니라 "주소 틀"만 저장한다 (예: /open/v1/accounts/{accountNumber})
--       → 계좌번호가 DB에 남지 않는다. 맞는 주소가 없으면 'UNMATCHED'

CREATE TABLE api_usage_logs (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    app_id        BIGINT        NOT NULL,          -- 어느 앱의 요청인가 (apps.id) → 통계·과금의 기준
    api_key_id    BIGINT        NOT NULL,          -- 어느 키로 들어왔나 (api_keys.id) → 키 교체 기간에 옛 키 사용 여부 확인용
    method        VARCHAR(10)   NOT NULL,          -- GET, POST ...
    endpoint      VARCHAR(200)  NOT NULL,          -- 주소 틀 (예: /open/v1/accounts/{accountNumber}) 또는 UNMATCHED
    status_code   SMALLINT      NOT NULL,          -- 응답 상태 코드 (200, 404, 429, 502 ...)
    latency_ms    INT           NOT NULL,          -- 우리 서버에서 걸린 시간 (밀리초)
    requested_at  DATETIME(3)   NOT NULL,          -- 요청 시각, 한국 시간(Asia/Seoul) 기준. 밀리초까지
    PRIMARY KEY (id),

    -- 통계 조회(WHERE app_id = ? AND requested_at >= ? AND requested_at < ?)를 빠르게
    -- 앱 번호로 먼저 좁히고, 그 안에서 시각 범위로 찾는다
    KEY idx_api_usage_logs_app_requested (app_id, requested_at),

    -- 없는 앱·키 번호로는 기록할 수 없게 DB가 막음
    -- (api_key_id에도 MySQL이 FK용 인덱스를 자동으로 만든다)
    CONSTRAINT fk_api_usage_logs_app FOREIGN KEY (app_id) REFERENCES apps (id),
    CONSTRAINT fk_api_usage_logs_api_key FOREIGN KEY (api_key_id) REFERENCES api_keys (id)
);
