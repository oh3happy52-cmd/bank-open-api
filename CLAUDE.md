# bank-open-api

## 프로젝트 소개
핀테크 기업이 bank_core(별도 프로젝트, 계좌·이체 코어 시스템)의 계좌 조회·이체 기능을 API로 사용할 수 있도록
API 키 인증, 호출량 제한(Rate Limit), 사용량 집계, 웹훅 알림을 제공하는 금융 API 플랫폼과 개발자 포털.

## 기술 스택
- Java 21, Spring Boot 4.1.3, Gradle
- MySQL + Flyway, Redis
- Spring Security + JWT
- React + Vite (frontend/ 폴더, 2주차부터)
- 테스트: JUnit, Testcontainers, k6

## 환경
- 패키지: com.example.bank_open_api
- 서버 포트 8081 (bank_core가 8080 사용)
- MySQL은 Docker Compose로 실행, 호스트 포트 3307

## 작업 규칙
1. 설명과 답변은 항상 한국어로 한다.
2. 사용자는 학생이다. 무엇을 왜 하는지 쉬운 말로 먼저 설명한 뒤 작업한다.
3. 새 파일 맨 위에는 이 파일이 무엇인지, 역할, 누가 쓰는지를 주석으로 적는다. 코드 주석은 자세히 단다.
4. 아래 핵심 영역은 코드를 바로 쓰지 않는다. 설계와 이유를 먼저 설명하고 사용자의 허락을 받은 뒤 작성한다.
   - API 키 발급·해시 저장, API 키 인증 필터
   - Redis 토큰 버킷 + Lua 스크립트
   - 웹훅 재전송(지수 백오프), HMAC 서명
5. 테스트를 실행하기 전에 사용자에게 예상 결과를 먼저 물어본다.
6. git commit은 사용자가 요청할 때만 한다. git push는 하지 않는다.
7. 비밀번호, 비밀 키 같은 비밀값은 코드에 직접 쓰지 않고 환경 변수나 .env로 분리하며, .env는 커밋하지 않는다.
8. 작업을 마치면 바꾼 파일 목록과 이유를 짧게 정리한다.
