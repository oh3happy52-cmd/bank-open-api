-- [무엇] 개발자(포털 회원) 테이블 생성
-- [역할] 개발자 포털에 회원가입한 핀테크 개발자의 로그인 정보(이메일, 비밀번호 해시, 이름)를 저장
-- [누가 쓰나] 서버를 켤 때 Flyway가 한 번 실행. 읽고 쓰는 쪽은 DeveloperService(회원가입·내 정보), AuthService(로그인)

CREATE TABLE developers (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    email          VARCHAR(255)  NOT NULL,   -- 로그인 아이디. 소문자로 바꿔서 저장
    password_hash  VARCHAR(100)  NOT NULL,   -- BCrypt 해시 (원래 비밀번호는 어디에도 저장하지 않음, 해시 길이는 60자)
    name           VARCHAR(50)   NOT NULL,
    created_at     DATETIME(6)   NOT NULL,   -- 가입 시각 (마이크로초까지)
    PRIMARY KEY (id),
    -- 같은 이메일로 두 번 가입하지 못하게 DB가 최종으로 막음
    -- (동시에 가입 요청이 두 개 와도 하나만 저장됨. 이 이름은 GlobalExceptionHandler가 409로 바꿀 때 사용)
    UNIQUE KEY uk_developers_email (email)
);
