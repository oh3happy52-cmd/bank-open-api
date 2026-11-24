<!--
  README.md
  - 무엇: bank-open-api 프로젝트 소개 및 실행 안내서
  - 역할: 프로젝트가 무엇인지, 어떻게 실행하는지, 어떤 API가 있는지 한눈에 보여 준다.
  - 누가: 이 저장소를 처음 받은 개발자, 프로젝트를 살펴보는 리뷰어·면접관
-->

# bank-open-api

핀테크 기업이 **API 키 하나로** bank_core(계좌·이체 코어 시스템)의 계좌 조회·이체 기능을 쓸 수 있게 해 주는 금융 오픈 API 플랫폼과 개발자 포털.

> 1주차 진행 상황과 트러블슈팅은 [docs/week1-summary.md](docs/week1-summary.md)에 정리했습니다.

## 기술 스택

| 분류 | 사용 기술 |
|---|---|
| 언어·프레임워크 | Java 21, Spring Boot 4.1.3, Gradle |
| 보안 | Spring Security, JWT(개발자 포털), API 키(오픈 API) |
| DB | MySQL 8.4 (Docker Compose), Flyway |
| 캐시 | Redis (2주차 Rate Limit부터) |
| 프론트엔드 | React + Vite (`frontend/`, 2주차부터) |
| 테스트 | JUnit 5, Testcontainers, k6 |

## 실행 방법

### 0. 준비물
- JDK 21, Docker Desktop
- 포트: **8081**(이 서버), **3308**(이 서버 MySQL), 8080(bank_core)

### 1. 환경 변수 파일 만들기

```bash
cp .env.example .env
# .env를 열어 비밀번호와 JWT_SECRET을 진짜 값으로 바꾼다
# JWT_SECRET 예: openssl rand -base64 48
```

`.env`는 `.gitignore`에 들어 있어 커밋되지 않습니다.

### 2. MySQL 실행

```bash
docker compose up -d
docker ps          # bank-open-api-mysql 이 (healthy) 가 되면 준비 완료
```

MySQL은 `127.0.0.1:3308`에만 열립니다. (bank_core MySQL이 3307을 쓰므로 3308 사용)

### 3. 서버 실행

```bash
./gradlew bootRun  # http://localhost:8081
```

서버가 시작될 때 Flyway가 테이블을 자동으로 만듭니다.

### 4. 테스트

```bash
./gradlew test     # Docker가 켜져 있어야 함 (Testcontainers가 MySQL을 띄움)
```

### 5. bank_core 연동 (계좌 조회 API를 쓸 때만 필요)

1. **bank_core 서버를 8080에서 실행**한다. 처음 한 번은 꼭 서버를 켜야 bank_core의 Flyway가 `member` 등 테이블을 만든다.
2. bank_core에 **연동 전용 일반 회원**(role=USER)을 만들고, 조회할 계좌를 그 회원 소유로 둔다. 관리자 계정은 모든 계좌가 보이므로 쓰지 않는다.
3. `.env`에 연동 정보를 적는다.
   ```properties
   BANK_CORE_BASE_URL=http://localhost:8080
   BANK_CORE_USERNAME=open-api-integration
   BANK_CORE_PASSWORD=...
   ```
4. 8080을 다른 프로그램이 잡고 있지 않은지 확인한다. (예: 로컬 nginx가 IPv4 8080을 잡고 있으면 요청이 nginx로 가서 502)
   ```bash
   lsof -nP -iTCP:8080 -sTCP:LISTEN
   ```

### 사용 예 (처음부터 계좌 조회까지)

```bash
# 회원가입 → 로그인(JWT)
curl -X POST localhost:8081/api/v1/developers/signup -H 'Content-Type: application/json' \
  -d '{"email":"dev@example.com","password":"password123","name":"개발자"}'
TOKEN=$(curl -s -X POST localhost:8081/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"dev@example.com","password":"password123"}' | jq -r .accessToken)

# 앱 등록 → API 키 발급 (원문은 이때 한 번만 보임)
curl -X POST localhost:8081/api/v1/apps -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"name":"my-app","description":"테스트 앱"}'
curl -X POST localhost:8081/api/v1/apps/1/keys -H "Authorization: Bearer $TOKEN"

# 오픈 API 호출
curl localhost:8081/open/v1/ping -H "X-API-Key: boa_live_..."
curl localhost:8081/open/v1/accounts/100-0001 -H "X-API-Key: boa_live_..."
```

## API 목록

### 개발자 포털 API (`/api/v1`) — JWT 인증

| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| POST | `/api/v1/developers/signup` | 없음 | 개발자 회원가입 |
| POST | `/api/v1/auth/login` | 없음 | 로그인, JWT 발급 |
| GET | `/api/v1/developers/me` | JWT | 내 정보 조회 |
| POST | `/api/v1/apps` | JWT | 앱 등록 |
| GET | `/api/v1/apps` | JWT | 내 앱 목록 |
| GET | `/api/v1/apps/{appId}` | JWT | 앱 상세 |
| POST | `/api/v1/apps/{appId}/keys` | JWT | API 키 발급 (원문 1회 노출, 앱당 활성 키 최대 2개) |
| GET | `/api/v1/apps/{appId}/keys` | JWT | API 키 목록 (원문·해시 없이 앞 12글자만) |
| DELETE | `/api/v1/apps/{appId}/keys/{keyId}` | JWT | API 키 폐기 |

### 오픈 API (`/open/v1`) — `X-API-Key` 헤더 인증

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/open/v1/ping` | 키 확인용. 호출한 appId와 키 앞부분을 돌려줌 |
| GET | `/open/v1/accounts/{accountNumber}` | bank_core 계좌 조회 (계좌번호·상태·잔액) |

### 에러 응답

모든 에러는 같은 JSON 모양으로 돌려줍니다. 주요 상태 코드:

| 코드 | 언제 |
|---|---|
| 400 | 요청 값이 올바르지 않음 |
| 401 | 로그인 토큰 또는 API 키가 없거나 틀림 |
| 404 | 주소·앱·키·계좌를 찾을 수 없음 (남의 것도 404) |
| 405 | 지원하지 않는 메서드 |
| 409 | 이메일·앱 이름 중복, 활성 키 2개 초과 |
| 502 | bank_core에 연결할 수 없음 |
| 504 | bank_core 응답이 늦음 |
