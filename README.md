<!--
  README.md
  - 무엇: bank-open-api 프로젝트 소개 및 실행 안내서
  - 역할: 프로젝트가 무엇인지, 어떻게 실행하는지, 어떤 API가 있는지 한눈에 보여 준다.
  - 누가: 이 저장소를 처음 받은 개발자, 프로젝트를 살펴보는 리뷰어·면접관
-->

# bank-open-api

핀테크 기업이 **API 키 하나로** bank_core(계좌·이체 코어 시스템)의 계좌 조회·이체 기능을 쓸 수 있게 해 주는 금융 오픈 API 플랫폼과 개발자 포털.

> 주차별 진행 상황·설계 결정·트러블슈팅: [1주차](docs/week1-summary.md) · [2주차](docs/week2-summary.md)

## 주요 기능

| 주차 | 기능 |
|---|---|
| 1주차 | 개발자 회원가입·로그인(JWT), 앱 등록, API 키 발급·폐기, `X-API-Key` 인증, bank_core 계좌 조회 전달 |
| 2주차 | **Rate Limit**: Redis 토큰 버킷(Lua), 앱당 양동이 1개, 초과 시 429 + `Retry-After`, Redis 장애 시 통과(fail-open) |
| | **사용량 기록 + 일별 통계**: 오픈 API 요청을 주소 틀로 비동기 기록(계좌번호 미저장), 날짜별·주소별 통계 API |
| | **개발자 포털(React)**: 회원가입·로그인·내 정보 화면 (`frontend/`) |

## 기술 스택

| 분류 | 사용 기술 |
|---|---|
| 언어·프레임워크 | Java 21, Spring Boot 4.1.3, Gradle |
| 보안 | Spring Security, JWT(개발자 포털), API 키(오픈 API) |
| DB | MySQL 8.4 (Docker Compose), Flyway |
| 캐시 | Redis 7 (Docker Compose, Rate Limit 토큰 양동이) |
| 프론트엔드 | React 19 + Vite, react-router-dom (`frontend/`) |
| 테스트 | JUnit 5, Testcontainers, k6 |

## 실행 방법

### 0. 준비물
- JDK 21, Docker Desktop, Node.js(프론트 실행 시, v24에서 확인)
- 포트: **8081**(이 서버), **3308**(이 서버 MySQL), **6380**(이 서버 Redis), **5173**(프론트 개발 서버), 8080(bank_core)

### 1. 환경 변수 파일 만들기

```bash
cp .env.example .env
# .env를 열어 비밀번호와 JWT_SECRET을 진짜 값으로 바꾼다
# JWT_SECRET 예: openssl rand -base64 48
```

`.env`는 `.gitignore`에 들어 있어 커밋되지 않습니다.

### 2. MySQL·Redis 실행

```bash
docker compose up -d
docker ps          # bank-open-api-mysql, bank-open-api-redis 가 (healthy) 가 되면 준비 완료
```

- MySQL은 `127.0.0.1:3308`에만 열립니다. (bank_core MySQL이 3307을 쓰므로 3308 사용)
- Redis는 `127.0.0.1:6380`에만 열립니다. (로컬에 설치된 Redis가 6379를 쓰는 경우가 많아 6380 사용)
- Redis가 꺼져 있어도 서버는 동작합니다. 이때 호출 제한은 적용되지 않습니다(fail-open).

### 3. 서버 실행

```bash
./gradlew bootRun  # http://localhost:8081
```

서버가 시작될 때 Flyway가 테이블을 자동으로 만듭니다.

### 4. 테스트

```bash
./gradlew test     # Docker가 켜져 있어야 함 (Testcontainers가 MySQL·Redis를 띄움)
```

### 5. 프론트엔드(개발자 포털) 실행

백엔드(3번)를 켜 둔 상태에서, 터미널을 하나 더 열어 실행합니다.

```bash
cd frontend
npm install        # 처음 한 번만 (라이브러리 받기)
npm run dev        # http://localhost:5173
```

- 브라우저에서 **http://localhost:5173** 으로 접속합니다. (Vite가 IPv6 `[::1]`에서 열려 `127.0.0.1:5173`으로는 열리지 않음)
- 화면의 `/api/...` 요청은 Vite 프록시가 `127.0.0.1:8081`로 전달하므로 백엔드에 CORS 설정이 필요 없습니다.
- 배포용 파일 만들기: `npm run build` (결과는 `frontend/dist/`, 커밋하지 않음)

### 6. bank_core 연동 (계좌 조회 API를 쓸 때만 필요)

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
| GET | `/api/v1/apps/{appId}/usage?from=&to=` | JWT | 사용량 통계. `daily`(날짜별, 빈 날 0) + `byEndpoint`(주소 틀별). 생략 시 최근 7일, 최대 31일 |

### 오픈 API (`/open/v1`) — `X-API-Key` 헤더 인증

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/open/v1/ping` | 키 확인용. 호출한 appId와 키 앞부분을 돌려줌 |
| GET | `/open/v1/accounts/{accountNumber}` | bank_core 계좌 조회 (계좌번호·상태·잔액) |

- **호출 제한**: 앱마다 양동이 10개, 초당 1개씩 채워짐 (`rate-limit.capacity`, `rate-limit.refill-per-second`)
  - 통과 시 헤더: `X-RateLimit-Limit`, `X-RateLimit-Remaining`
  - 초과 시: 429 `RATE_LIMIT_EXCEEDED` + `Retry-After`(초)
- **사용량 기록**: 인증을 통과한 요청은 결과(200·404·429·502 …)와 상관없이 기록 (401은 기록 안 함)

### 에러 응답

모든 에러는 같은 JSON 모양으로 돌려줍니다. 주요 상태 코드:

| 코드 | 언제 |
|---|---|
| 400 | 요청 값이 올바르지 않음, 통계 조회 기간 오류(시작일 > 종료일, 31일 초과) |
| 401 | 로그인 토큰 또는 API 키가 없거나 틀림 |
| 404 | 주소·앱·키·계좌를 찾을 수 없음 (남의 것도 404) |
| 405 | 지원하지 않는 메서드 |
| 409 | 이메일·앱 이름 중복, 활성 키 2개 초과 |
| 429 | 앱의 호출 한도 초과 (`Retry-After` 초 뒤 다시 시도) |
| 502 | bank_core에 연결할 수 없음 |
| 504 | bank_core 응답이 늦음 |
