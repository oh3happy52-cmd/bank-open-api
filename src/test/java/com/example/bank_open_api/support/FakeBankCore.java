package com.example.bank_open_api.support;

// [무엇] 테스트용 가짜 bank_core 서버
// [역할] JDK에 들어 있는 작은 HTTP 서버(com.sun.net.httpserver)를 진짜 포트로 띄워 bank_core 흉내를 낸다
//        - POST /api/v1/auth/login: 아이디·비밀번호가 맞으면 "fake-jwt-번호" 토큰 발급 (발급한 토큰 목록을 기억)
//        - GET /api/v1/accounts/{번호}: 토큰이 목록에 있으면 정해 둔 동작(정상 / 상태 코드 / 느림)으로 응답, 없으면 401
//        진짜 네트워크를 쓰므로 "응답이 느림(타임아웃)"도 실제와 똑같이 재현된다
//        (MockRestServiceServer는 네트워크를 흉내만 내서 진짜 타임아웃을 만들 수 없음)
// [누가 쓰나] BankCoreAccountTest
//
// 테스트마다 reset()으로 동작·횟수를 처음 상태로 되돌린다.
// 발급한 토큰 목록은 reset()에서 지우지 않는다 → 앞 테스트에서 받은 토큰을 계속 재사용하는 진짜 상황과 같게.

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class FakeBankCore {

    // 계좌 조회에 어떻게 답할지
    public enum Mode {
        OK,                   // 200 + 계좌 정보
        STATUS,               // statusToReturn 상태 코드 + {code, message}
        SLOW,                 // delayMillis만큼 기다렸다가 200
        ALWAYS_UNAUTHORIZED   // 토큰과 상관없이 항상 401 (새 토큰도 거절되는 상황)
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();   // 동시 요청을 한꺼번에 처리
    private final String loginId;
    private final String password;

    private final Set<String> validTokens = ConcurrentHashMap.newKeySet();
    private final AtomicInteger tokenSeq = new AtomicInteger();

    // 테스트가 확인하는 횟수
    private final AtomicInteger loginCount = new AtomicInteger();
    private final AtomicInteger accountCallCount = new AtomicInteger();
    private final AtomicInteger unauthorizedCount = new AtomicInteger();

    // 테스트가 바꾸는 동작 (여러 스레드가 읽으므로 volatile)
    private volatile Mode mode = Mode.OK;
    private volatile int statusToReturn = 500;
    private volatile long delayMillis = 0;
    private volatile long loginDelayMillis = 0;
    private volatile long expiresIn = 3600;
    private volatile long balance = 50_000;
    private volatile String lastAuthorization;

    private FakeBankCore(String loginId, String password) throws IOException {
        this.loginId = loginId;
        this.password = password;
        // 포트 0: 비어 있는 아무 포트나 씀 (다른 프로그램과 겹치지 않게)
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/auth/login", this::handleLogin);
        server.createContext("/api/v1/accounts/", this::handleAccount);
        server.setExecutor(executor);
    }

    // 서버를 켜서 돌려줌
    public static FakeBankCore start(String loginId, String password) {
        try {
            FakeBankCore fake = new FakeBankCore(loginId, password);
            fake.server.start();
            return fake;
        } catch (IOException e) {
            throw new IllegalStateException("가짜 bank_core를 켤 수 없습니다", e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // 동작과 횟수를 처음 상태로 (발급한 토큰 목록은 유지)
    public void reset() {
        mode = Mode.OK;
        statusToReturn = 500;
        delayMillis = 0;
        loginDelayMillis = 0;
        expiresIn = 3600;
        balance = 50_000;
        lastAuthorization = null;
        loginCount.set(0);
        accountCallCount.set(0);
        unauthorizedCount.set(0);
    }

    // ───── 테스트가 동작을 바꾸는 메서드 ─────

    public void respondWithStatus(int status) {
        this.mode = Mode.STATUS;
        this.statusToReturn = status;
    }

    public void respondSlowly(long delayMillis) {
        this.mode = Mode.SLOW;
        this.delayMillis = delayMillis;
    }

    public void alwaysUnauthorized() {
        this.mode = Mode.ALWAYS_UNAUTHORIZED;
    }

    public void setBalance(long balance) {
        this.balance = balance;
    }

    public void setLoginDelayMillis(long loginDelayMillis) {
        this.loginDelayMillis = loginDelayMillis;
    }

    public void setExpiresIn(long expiresIn) {
        this.expiresIn = expiresIn;
    }

    // 지금까지 발급한 토큰을 전부 무효로 (bank_core가 재시작되며 비밀 키가 바뀐 상황 흉내)
    public void invalidateAllTokens() {
        validTokens.clear();
    }

    // ───── 테스트가 확인하는 값 ─────

    public int loginCount() {
        return loginCount.get();
    }

    public int accountCallCount() {
        return accountCallCount.get();
    }

    public int unauthorizedCount() {
        return unauthorizedCount.get();
    }

    public String lastAuthorization() {
        return lastAuthorization;
    }

    // ───── 요청 처리 ─────

    private void handleLogin(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            loginCount.incrementAndGet();
            sleep(loginDelayMillis);

            // bank_core 로그인 요청 모양 {"loginId": ..., "password": ...} 인지 간단히 확인
            boolean matches = body.contains("\"loginId\":\"" + loginId + "\"")
                    && body.contains("\"password\":\"" + password + "\"");
            if (!matches) {
                send(exchange, 401, "{\"code\":\"INVALID_CREDENTIALS\",\"message\":\"아이디 또는 비밀번호가 올바르지 않습니다\"}");
                return;
            }
            String token = "fake-jwt-" + tokenSeq.incrementAndGet();
            validTokens.add(token);
            send(exchange, 200, "{\"accessToken\":\"" + token + "\",\"tokenType\":\"Bearer\",\"expiresIn\":" + expiresIn + "}");
        }
    }

    private void handleAccount(HttpExchange exchange) throws IOException {
        try (exchange) {
            accountCallCount.incrementAndGet();
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            lastAuthorization = authorization;
            String token = authorization != null && authorization.startsWith("Bearer ")
                    ? authorization.substring("Bearer ".length()) : null;

            if (mode == Mode.ALWAYS_UNAUTHORIZED || token == null || !validTokens.contains(token)) {
                unauthorizedCount.incrementAndGet();
                send(exchange, 401, "{\"code\":\"UNAUTHORIZED\",\"message\":\"로그인이 필요합니다\"}");
                return;
            }

            String accountNumber = exchange.getRequestURI().getPath().substring("/api/v1/accounts/".length());
            switch (mode) {
                case STATUS -> send(exchange, statusToReturn,
                        "{\"code\":\"FAKE_" + statusToReturn + "\",\"message\":\"가짜 bank_core 응답\"}");
                case SLOW -> {
                    sleep(delayMillis);
                    send(exchange, 200, accountJson(accountNumber));
                }
                default -> send(exchange, 200, accountJson(accountNumber));
            }
        } catch (IOException e) {
            // 느림 테스트: 우리 서버가 기다리다 먼저 연결을 끊으면 쓰기에 실패한다 (예상된 일이라 무시)
        }
    }

    // bank_core AccountResponse와 같은 모양
    private String accountJson(String accountNumber) {
        return "{\"accountNumber\":\"" + accountNumber + "\",\"ownerName\":\"연동회원\","
                + "\"accountType\":\"CUSTOMER\",\"status\":\"ACTIVE\",\"balance\":" + balance + "}";
    }

    private static void send(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
