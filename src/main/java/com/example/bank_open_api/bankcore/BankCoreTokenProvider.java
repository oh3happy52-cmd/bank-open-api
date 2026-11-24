package com.example.bank_open_api.bankcore;

// [무엇] bank_core 로그인 토큰(JWT) 보관함
// [역할] ① bank_core에 "연동 전용 회원"으로 로그인해서 받은 JWT를 메모리에 보관하고 재사용한다
//        ② 만료 30초 전이 되면 다음 요청 때 다시 로그인한다
//        ③ bank_core가 401(토큰 거절)을 주면 BankCoreClient가 renewAfterRejected()로 새 토큰을 받아 한 번만 재시도한다
//        ④ 여러 요청이 동시에 와도 로그인은 한 번만 일어나게 자물쇠(loginLock)를 건다
// [누가 쓰나] BankCoreClient
//
// ── 동시에 여러 요청이 오면? (자물쇠 + "다시 확인") ──
//   요청 10개가 동시에 "토큰이 없다(또는 거절됐다)"를 발견해도
//   1) 자물쇠를 먼저 잡은 1개만 로그인하고, 나머지 9개는 자물쇠 앞에서 기다린다
//   2) 자물쇠를 받은 다음 요청은 "그새 누가 새 토큰을 받아 놨나?"를 다시 확인하고, 있으면 로그인 없이 그걸 쓴다
//   → bank_core에는 로그인 요청이 1번만 간다
//   (synchronized 대신 ReentrantLock: 같은 일을 하지만, 나중에 가상 스레드를 써도 스레드를 붙잡지 않는다)
//
// ── 비밀값 규칙 ──
//   비밀번호와 토큰 값은 어떤 로그에도 남기지 않는다. 로그에는 상태 코드와 걸린 시간만.

import com.example.bank_open_api.common.exception.BusinessException;
import com.example.bank_open_api.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
public class BankCoreTokenProvider {

    // 만료 시각보다 이만큼 일찍 새로 로그인한다 (요청이 가는 도중에 만료되는 일을 막기 위해)
    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(30);

    // bank_core 로그인 요청 / 응답 모양 (bank_core AuthController.LoginRequest / LoginResponse와 같은 칸 이름)
    // toString()을 직접 정의: record 기본 toString은 모든 칸을 찍어서, 혹시 디버그 로그에 찍혀도 비밀값이 나가지 않게 가림
    record LoginRequest(String loginId, String password) {
        @Override
        public String toString() {
            return "LoginRequest[loginId=" + loginId + ", password=****]";
        }
    }

    record LoginResponse(String accessToken, String tokenType, long expiresIn) {
        @Override
        public String toString() {
            return "LoginResponse[accessToken=****, tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
        }
    }

    // 보관 중인 토큰과 "이 시각이 지나면 새로 로그인" 시각
    private record CachedToken(String value, Instant refreshAt) {
        boolean isFresh() {
            return Instant.now().isBefore(refreshAt);
        }
    }

    private final RestClient restClient;
    private final BankCoreProperties properties;

    // 로그인은 한 번에 한 요청만
    private final ReentrantLock loginLock = new ReentrantLock();

    // volatile: 한 스레드가 바꾼 토큰을 다른 스레드가 바로 보게 함 (자물쇠 없이 읽는 빠른 길에서 필요)
    private volatile CachedToken cached;

    public BankCoreTokenProvider(@Qualifier(BankCoreConfig.REST_CLIENT) RestClient restClient,
                                 BankCoreProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /**
     * 지금 쓸 토큰. 보관 중인 토큰이 아직 유효하면 그대로, 없거나 만료가 가까우면 로그인해서 새로 받는다
     */
    public String getToken() {
        // 빠른 길: 대부분의 요청은 자물쇠 없이 여기서 끝난다
        CachedToken current = cached;
        if (current != null && current.isFresh()) {
            return current.value();
        }

        loginLock.lock();
        try {
            // 다시 확인: 자물쇠를 기다리는 동안 다른 요청이 이미 로그인했을 수 있다
            current = cached;
            if (current != null && current.isFresh()) {
                return current.value();
            }
            return loginAndStore();
        } finally {
            loginLock.unlock();
        }
    }

    /**
     * bank_core가 rejectedToken을 401로 거절했을 때 새 토큰 받기
     * - 다른 요청이 이미 새 토큰으로 바꿔 놨으면 로그인 없이 그 토큰을 돌려준다
     * - 아니면 로그인해서 새로 받는다
     */
    public String renewAfterRejected(String rejectedToken) {
        loginLock.lock();
        try {
            CachedToken current = cached;
            if (current != null && !current.value().equals(rejectedToken) && current.isFresh()) {
                return current.value();
            }
            cached = null;   // 거절된 토큰은 버린다 (로그인이 실패해도 다음 요청이 다시 로그인을 시도하게)
            return loginAndStore();
        } finally {
            loginLock.unlock();
        }
    }

    // 로그인해서 받은 토큰을 보관하고 돌려준다 (loginLock을 잡은 상태에서만 부른다)
    private String loginAndStore() {
        CachedToken token = login();
        cached = token;
        return token.value();
    }

    // bank_core POST /api/v1/auth/login
    private CachedToken login() {
        if (!properties.hasCredentials()) {
            // 설정 실수는 운영자가 바로 알아야 하므로 error. 값 자체는 찍지 않는다
            log.error("bank_core 연동 계정 설정 없음: .env의 BANK_CORE_USERNAME, BANK_CORE_PASSWORD를 확인하세요");
            throw new BusinessException(ErrorCode.BANK_CORE_UNAVAILABLE);
        }

        long start = System.nanoTime();
        try {
            return restClient.post()
                    .uri("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new LoginRequest(properties.username(), properties.password()))
                    // exchange: 상태 코드를 우리가 직접 보고 처리 (4xx·5xx에서 예외를 자동으로 던지지 않음)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        long elapsed = BankCoreFailures.elapsedMillis(start);

                        if (status == 200) {
                            LoginResponse body = response.bodyTo(LoginResponse.class);
                            if (body == null || body.accessToken() == null || body.accessToken().isBlank()) {
                                log.error("bank_core 로그인 응답에 토큰 없음: status=200, elapsedMs={}", elapsed);
                                throw new BusinessException(ErrorCode.BANK_CORE_UNAVAILABLE);
                            }
                            log.info("bank_core 로그인 성공: status=200, elapsedMs={}", elapsed);
                            return new CachedToken(body.accessToken(), refreshAt(body.expiresIn()));
                        }

                        if (status == 401) {
                            // 연동 계정 아이디·비밀번호가 틀림 → 사람이 고쳐야 하는 문제라 error
                            log.error("bank_core 로그인 실패(연동 계정 아이디·비밀번호 확인 필요): status=401, elapsedMs={}",
                                    elapsed);
                        } else {
                            log.warn("bank_core 로그인 실패: status={}, elapsedMs={}", status, elapsed);
                        }
                        throw new BusinessException(ErrorCode.BANK_CORE_UNAVAILABLE);
                    });
        } catch (ResourceAccessException e) {
            // 꺼져 있음·연결 실패 → 502, 제한 시간 초과 → 504
            log.warn("bank_core 로그인 실패: status={}, elapsedMs={}",
                    BankCoreFailures.label(e), BankCoreFailures.elapsedMillis(start));
            throw BankCoreFailures.toBusinessException(e);
        }
    }

    // "새로 로그인할 시각" = 지금 + 유효 시간 - 30초
    // 유효 시간이 30초 이하로 아주 짧으면 지금 바로(= 다음 요청 때 새로 로그인)
    private static Instant refreshAt(long expiresInSeconds) {
        Instant now = Instant.now();
        Instant refreshAt = now.plusSeconds(expiresInSeconds).minus(REFRESH_MARGIN);
        return refreshAt.isAfter(now) ? refreshAt : now;
    }
}
