package com.example.bank_open_api.support;

// [무엇] 테스트용 가짜 웹훅 수신 서버 (핀테크 앱 서버 흉내)
// [역할] JDK 내장 HTTP 서버를 진짜 포트로 띄워 POST /webhook 을 받는다
//        - 받은 요청의 헤더와 본문 바이트를 그대로 기록 (서명 검증은 테스트가 직접)
//        - 응답 상태 코드(기본 200)와 응답 지연(타임아웃 재현)을 테스트가 바꿀 수 있다
//        진짜 네트워크를 쓰므로 "응답이 느림(타임아웃)"도 실제와 똑같이 재현된다
// [누가 쓰나] WebhookDispatchTest
//
// 다음 단계의 "모의 핀테크 서버"와는 다르다: 이건 테스트 안에서만 쓰는 기록용 서버

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

public class FakeWebhookReceiver {

    // 받은 요청 1건
    public record Received(Map<String, String> headers, byte[] body) {
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile long delayMillis = 0;

    private FakeWebhookReceiver() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());   // 동시 요청을 한꺼번에 처리
    }

    public static FakeWebhookReceiver start() {
        try {
            FakeWebhookReceiver receiver = new FakeWebhookReceiver();
            receiver.server.start();
            return receiver;
        } catch (IOException e) {
            throw new IllegalStateException("가짜 웹훅 수신 서버를 켤 수 없습니다", e);
        }
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook";
    }

    public void reset() {
        received.clear();
        status = 200;
        delayMillis = 0;
    }

    public void respondWith(int status) {
        this.status = status;
    }

    public void respondSlowly(long delayMillis) {
        this.delayMillis = delayMillis;
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] body = exchange.getRequestBody().readAllBytes();
            Map<String, String> headers = new ConcurrentHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                    headers.put(name.toLowerCase(Locale.ROOT), String.join(",", values)));
            received.add(new Received(headers, body));

            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            exchange.sendResponseHeaders(status, -1);   // 본문 없음
        } catch (IOException e) {
            // 타임아웃 테스트: 보내는 쪽이 먼저 연결을 끊으면 응답 쓰기에 실패한다 (예상된 일)
        }
    }
}
