-- [무엇] 앱 테이블 생성
-- [역할] 개발자가 개발자 포털에 등록한 앱(서비스)을 저장. 나중에 API 키는 이 앱 단위로 발급한다
-- [누가 쓰나] 서버를 켤 때 Flyway가 한 번 실행. 읽고 쓰는 쪽은 AppService(등록·목록·상세)

CREATE TABLE apps (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    developer_id  BIGINT        NOT NULL,          -- 앱 주인 (developers.id). 토큰의 subject에서만 정해짐
    name          VARCHAR(50)   NOT NULL,          -- 앱 이름 (앞뒤 공백 제거 후 저장)
    description   VARCHAR(200)  NULL,              -- 앱 설명 (선택)
    created_at    DATETIME(6)   NOT NULL,          -- 등록 시각 (마이크로초까지)
    PRIMARY KEY (id),

    -- 같은 개발자 안에서는 같은 이름의 앱을 두 개 만들 수 없음 (다른 개발자는 같은 이름 가능)
    -- MySQL 기본 비교 규칙은 대소문자를 구분하지 않으므로 "MyApp"과 "myapp"도 중복으로 막힘
    -- 이 이름은 GlobalExceptionHandler가 동시 등록 충돌을 409로 바꿀 때 사용
    UNIQUE KEY uk_apps_developer_name (developer_id, name),

    -- 내 앱 목록(WHERE developer_id = ? ORDER BY created_at DESC)을 빠르게 찾기 위한 인덱스
    KEY idx_apps_developer_created (developer_id, created_at),

    -- 없는 개발자 번호로는 앱을 만들 수 없게 DB가 막음
    CONSTRAINT fk_apps_developer FOREIGN KEY (developer_id) REFERENCES developers (id)
);
