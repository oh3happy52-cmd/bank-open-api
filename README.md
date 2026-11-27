<!--
  README.md
  - 무엇: bank-open-api 프로젝트 소개 및 실행 안내서
  - 역할: 프로젝트가 무엇인지, 어떻게 실행하는지, 어떤 API가 있는지 한눈에 보여 준다.
  - 누가: 이 저장소를 처음 받은 개발자, 프로젝트를 살펴보는 리뷰어·면접관
-->

# bank-open-api

핀테크 기업이 **API 키 하나로** bank_core(계좌·이체 코어 시스템)의 계좌 조회·이체 기능을 쓸 수 있게 해 주는 금융 오픈 API 플랫폼과 개발자 포털.

> 주차별 진행 상황·설계 결정·트러블슈팅: [1주차](docs/week1-summary.md) · [2주차](docs/week2-summary.md) · [3주차](docs/week3-summary.md)

## 주요 기능

| 주차 | 기능 |
|---|---|
| 1주차 | 개발자 회원가입·로그인(JWT), 앱 등록, API 키 발급·폐기, `X-API-Key` 인증, bank_core 계좌 조회 전달 |
| 2주차 | **Rate Limit**: Redis 토큰 버킷(Lua), 앱당 양동이 1개, 초과 시 429 + `Retry-After`, Redis 장애 시 통과(fail-open) |
| | **사용량 기록 + 일별 통계**: 오픈 API 요청을 주소 틀로 비동기 기록(계좌번호 미저장), 날짜별·주소별 통계 API |
| | **개발자 포털(React)**: 회원가입·로그인·내 정보 화면 (`frontend/`) |
| 3주차 | **이체 API**: `POST /open/v1/transfers` + `Idempotency-Key`(앱별로 분리해 bank_core에 전달), 504면 같은 키로 재시도 |
| | **웹훅**: 이체 완료 알림(`transfer.completed`), 아웃박스 + 1초 발송 일꾼, HMAC-SHA256 서명, 지수 백오프 재전송(최대 6번) |
| | **모의 핀테크 서버**: 웹훅 받는 쪽 흉내(`/mock-fintech/**`) — 서명 검증·중복 제거·장애 주입 |
| | **개발자 포털**: 내 앱 목록·등록, 앱 상세, API 키 발급(원문 1회 표시)·폐기 화면 |

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
# WEBHOOK_ENCRYPTION_KEY 예: openssl rand -base64 32
```

`.env`는 `.gitignore`에 들어 있어 커밋되지 않습니다.

| 변수 | 설명 |
|---|---|
| `DB_ROOT_PASSWORD`, `DB_USERNAME`, `DB_PASSWORD` | MySQL 계정 (docker-compose·Spring) |
| `JWT_SECRET` | 로그인 토큰 서명 키. 32바이트 이상 |
| `BANK_CORE_BASE_URL`, `BANK_CORE_USERNAME`, `BANK_CORE_PASSWORD` | bank_core 연동 (6번 참고) |
| `WEBHOOK_ENCRYPTION_KEY` | 웹훅 서명 비밀키를 DB에 **암호화(AES-256-GCM)** 해서 저장할 때 쓰는 키. **Base64로 적은 정확히 32바이트**여야 하며, 없거나 길이가 틀리면 서버가 시작되지 않는다. **값을 바꾸면 이미 저장된 웹훅 비밀키를 풀 수 없으므로** 모든 앱이 비밀키를 다시 발급(rotate)해야 한다 |
| `MOCK_FINTECH_ENABLED` (선택) | 모의 핀테크 서버 켜기/끄기. 기본 `true`(로컬 실험용). **운영에서는 반드시 `false`** |

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

| 화면 | 주소 | 할 수 있는 일 |
|---|---|---|
| 회원가입·로그인 | `/signup`, `/login` | 가입, 로그인 (토큰은 sessionStorage) |
| 내 앱 | `/apps` (로그인 후 기본 화면) | 내 앱 목록, 앱 등록 |
| 앱 상세 | `/apps/:appId` | 앱 정보, API 키 목록, **키 발급**(원문은 상자에 딱 1번만 표시 — 닫으면 다시 볼 수 없음), 키 폐기 |
| 내 정보 | `/me` | 이메일·이름 |

- `/apps`, `/apps/:appId`, `/me`는 로그인해야 열린다. 토큰이 없거나 만료(401)되면 `/login`으로 보낸다.
- 남의 앱·없는 앱 주소로 들어가면 "앱을 찾을 수 없습니다"가 나온다.

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

# 이체 (Idempotency-Key = 이 이체의 번호표. 새 이체마다 새 값)
curl -X POST localhost:8081/open/v1/transfers -H "X-API-Key: boa_live_..." \
  -H "Idempotency-Key: order-20261127-0001" -H 'Content-Type: application/json' \
  -d '{"fromAccountNumber":"100-0001","toAccountNumber":"100-0002","amount":1000}'
```

### 이체 API와 Idempotency-Key

- 응답을 못 받았거나 **504(`BANK_CORE_TIMEOUT`)·502**를 받으면, 이체가 됐는지 알 수 없는 상태입니다. **같은 `Idempotency-Key`로 다시 요청**하세요. 이미 처리된 이체면 처음 결과(같은 `transferId`)를 돌려주고 돈은 다시 나가지 않습니다. 서버는 이중 이체를 막기 위해 자동으로 재전송하지 않습니다.
- 다른 키로 다시 보내면 **새 이체**가 됩니다.
- 같은 키로 **다른 내용**(금액·계좌)을 보내면 422 `IDEMPOTENCY_KEY_REUSED`, 같은 키 요청이 아직 처리 중이면 409 `DUPLICATE_REQUEST`.
- 키 형식: 영문·숫자·`-`·`_` 1~64자. 앱끼리 같은 키를 써도 서로 영향이 없습니다(내부에서 `app-{appId}:{키}`로 바꿔 전달).

### 웹훅 (이체 완료 알림)

이체가 성공하면 등록한 주소로 `POST` 알림을 보냅니다.

```bash
# 1) 웹훅 주소 등록 → 201 응답의 secret(whsec_...)은 이때 한 번만 보임. 안전한 곳에 보관
curl -X PUT localhost:8081/api/v1/apps/1/webhook -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"url":"https://fintech.example.com/webhooks"}'

# 2) 전송 기록 확인 (최신순)
curl localhost:8081/api/v1/apps/1/webhook/deliveries -H "Authorization: Bearer $TOKEN"
```

알림 본문과 헤더:

```http
POST /webhooks
Content-Type: application/json
X-Webhook-Id: evt_...                 ← 같은 알림을 다시 보내면 같은 값
X-Webhook-Timestamp: 1795743600       ← 유닉스 초
X-Webhook-Signature: v1=3f9a...       ← HMAC-SHA256 16진수

{"eventId":"evt_...","type":"transfer.completed","createdAt":"2026-11-27T10:00:00+09:00",
 "data":{"transferId":42,"fromAccountNumber":"100-0001","toAccountNumber":"100-0002","amount":1000,"status":"COMPLETED"}}
```

**받는 쪽이 해야 할 일 (서명 검증 방법)**

1. **받은 본문 바이트 그대로** `X-Webhook-Timestamp + "." + 본문`을 만든다. (JSON으로 읽었다가 다시 문자열로 만들면 공백·순서가 바뀌어 서명이 맞지 않음)
2. `hex(HMAC-SHA256(비밀키 whsec_... 의 UTF-8 바이트, 위 문자열))`을 계산해 `v1=` 뒤의 값과 **상수 시간 비교**(예: Java `MessageDigest.isEqual`)한다. 다르면 401.
3. 타임스탬프가 지금과 **5분 넘게** 차이 나면 거절한다. (가로챈 알림을 나중에 다시 보내는 공격 방지)
4. `X-Webhook-Id`를 저장해 두고 **이미 받은 값이면 처리하지 않고 200**을 준다. 알림은 "적어도 한 번" 보내므로 중복이 올 수 있다.
5. 처리에 성공하면 **2xx**로 빨리 응답한다. 2xx가 아니거나 3초 안에 응답하지 않으면 실패로 보고 **2·4·8·16·32초 뒤** 다시 보낸다. 6번 모두 실패하면 `FAILED`로 멈춘다.

비밀키가 새어 나갔다면 `POST /api/v1/apps/{appId}/webhook/secret/rotate`로 바꾸세요. 옛 키는 바로 쓸 수 없게 됩니다.

### 모의 핀테크 서버 (웹훅 받는 쪽 실험)

같은 서버(8081) 안에 웹훅을 "받는 쪽"을 흉내 내는 실험용 API가 있습니다. **인증이 없으므로 로컬에서만** 쓰고, 운영에서는 `MOCK_FINTECH_ENABLED=false`로 끕니다.

```bash
# 1) 웹훅 주소를 모의 서버로 등록 → 응답의 secret을 복사
curl -X PUT localhost:8081/api/v1/apps/1/webhook -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"url":"http://127.0.0.1:8081/mock-fintech/webhooks"}'

# 2) 모의 서버에 같은 비밀키 넣기 (메모리에만 저장, 서버 재시작 시 사라짐)
curl -X PUT localhost:8081/mock-fintech/config -H 'Content-Type: application/json' -d '{"secret":"whsec_..."}'

# 3) (선택) 장애 흉내: 실패 확률(0~1), 응답 지연(ms), "처리는 하고 500 응답"
curl -X PUT localhost:8081/mock-fintech/faults -H 'Content-Type: application/json' \
  -d '{"failRate":0,"delayMillis":0,"failAfterProcessing":true}'

# 4) 이체를 한 뒤 받은 알림 보기 (duplicateCount = 중복으로 걸러 낸 횟수)
curl localhost:8081/mock-fintech/received

# 5) 받은 목록 초기화
curl -X DELETE localhost:8081/mock-fintech/received
```

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/mock-fintech/webhooks` | 웹훅 받기. 서명·시각 검사(실패 401) → 중복 제거 → 기록. 비밀키 미설정 503 |
| PUT | `/mock-fintech/config` | `{secret}` 웹훅 비밀키 설정 |
| PUT | `/mock-fintech/faults` | `{failRate, delayMillis, failAfterProcessing}` 장애 주입 |
| GET | `/mock-fintech/received` | 받은 알림 목록 |
| DELETE | `/mock-fintech/received` | 목록 초기화 |

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
| PUT | `/api/v1/apps/{appId}/webhook` | JWT | 웹훅 주소 등록(201, 비밀키 원문 1회) · 수정(200, 꺼져 있었으면 다시 켬) |
| GET | `/api/v1/apps/{appId}/webhook` | JWT | 웹훅 설정 조회 (`url`, `secretPrefix`, `enabled`) |
| DELETE | `/api/v1/apps/{appId}/webhook` | JWT | 웹훅 끄기 (설정은 남음, 보낼 예정이던 알림은 `CANCELED`) |
| POST | `/api/v1/apps/{appId}/webhook/secret/rotate` | JWT | 웹훅 비밀키 교체 (새 원문 1회) |
| GET | `/api/v1/apps/{appId}/webhook/deliveries?page=&size=` | JWT | 웹훅 전송 기록 (상태·시도 횟수·마지막 오류, 최신순) |

### 오픈 API (`/open/v1`) — `X-API-Key` 헤더 인증

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/open/v1/ping` | 키 확인용. 호출한 appId와 키 앞부분을 돌려줌 |
| GET | `/open/v1/accounts/{accountNumber}` | bank_core 계좌 조회 (계좌번호·상태·잔액) |
| POST | `/open/v1/transfers` | 이체. `Idempotency-Key` 헤더 필수. 201 `{transferId, fromAccountNumber, toAccountNumber, amount, status}` |

- **호출 제한**: 앱마다 양동이 10개, 초당 1개씩 채워짐 (`rate-limit.capacity`, `rate-limit.refill-per-second`)
  - 통과 시 헤더: `X-RateLimit-Limit`, `X-RateLimit-Remaining`
  - 초과 시: 429 `RATE_LIMIT_EXCEEDED` + `Retry-After`(초)
- **사용량 기록**: 인증을 통과한 요청은 결과(200·404·429·502 …)와 상관없이 기록 (401은 기록 안 함)

### 에러 응답

모든 에러는 같은 JSON 모양으로 돌려줍니다. 주요 상태 코드:

| 코드 | 언제 |
|---|---|
| 400 | 요청 값이 올바르지 않음, 통계 조회 기간 오류(시작일 > 종료일, 31일 초과), `Idempotency-Key` 없음·형식 오류, 같은 계좌로 이체, 웹훅 주소 형식 오류 |
| 401 | 로그인 토큰 또는 API 키가 없거나 틀림 |
| 404 | 주소·앱·키·계좌를 찾을 수 없음 (남의 것도 404) |
| 405 | 지원하지 않는 메서드 |
| 409 | 이메일·앱 이름 중복, 활성 키 2개 초과, 같은 `Idempotency-Key`의 이체가 처리 중 |
| 422 | 잔액 부족, 사용할 수 없는 계좌, 같은 키로 다른 내용의 이체 |
| 429 | 앱의 호출 한도 초과 (`Retry-After` 초 뒤 다시 시도) |
| 502 | bank_core에 연결할 수 없음 (이체라면 같은 키로 재시도) |
| 504 | bank_core 응답이 늦음 (이체라면 결과를 모름 → **같은 키로 재시도**) |
