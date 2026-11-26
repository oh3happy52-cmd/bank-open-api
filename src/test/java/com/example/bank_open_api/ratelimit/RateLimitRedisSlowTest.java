package com.example.bank_open_api.ratelimit;

// [무엇] Redis가 "응답하지 않을 때"의 호출 제한 테스트 (명령 제한 시간 500ms + fail-open)
// [역할] 접속은 받아 주지만 아무 대답도 하지 않는 가짜 서버(블랙홀)를 Redis 주소로 넣는다.
//        Redis가 멈췄거나 아주 느린 상황과 같다. 요청이 오래 붙잡히지 않고(약 0.5초) 통과하는지 확인
//        (RateLimitRedisDownTest는 "연결 거부" = 바로 실패하는 경우, 여기는 "대답 없음" = 기다리다 실패하는 경우)
// [누가 쓰나] ./gradlew test 실행 시 JUnit이 실행 (Docker 필요: Testcontainers MySQL)

import com.example.bank_open_api.support.OpenApiFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "jwt.secret=test-only-jwt-secret-key-0123456789abcdef",
        "rate-limit.capacity=1"
})
@Import(RateLimitRedisDownTest.MySqlOnly.class)   // MySQL만 (Redis 컨테이너는 띄우지 않음)
@ExtendWith(OutputCaptureExtension.class)
class RateLimitRedisSlowTest {

    // 접속은 받고 아무것도 읽거나 쓰지 않는 서버. 받은 연결은 닫히지 않게 목록에 붙잡아 둔다
    private static final ServerSocket BLACK_HOLE = startBlackHole();
    private static final List<Socket> HELD = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", BLACK_HOLE::getLocalPort);
    }

    @Autowired
    WebApplicationContext context;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void Redis가_대답하지_않아도_제한_시간_뒤_통과한다(CapturedOutput output) throws Exception {
        OpenApiFixture.Issued issued = OpenApiFixture.issueApiKey(mockMvc);

        for (int i = 0; i < 2; i++) {
            long started = System.nanoTime();
            mockMvc.perform(get("/open/v1/ping").header("X-API-Key", issued.apiKey()))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("X-RateLimit-Limit"));
            long tookMillis = (System.nanoTime() - started) / 1_000_000;
            // 제한 시간 500ms 근처에서 풀려나야 한다 (무한정 기다리지 않음). 컴퓨터가 느릴 수 있어 2초까지 허용
            assertThat(tookMillis).isLessThan(2_000);
        }

        assertThat(output.getAll())
                .contains("fail-open")
                .contains("appId=" + issued.appId())
                .doesNotContain(issued.apiKey());
    }

    private static ServerSocket startBlackHole() {
        try {
            ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        HELD.add(server.accept());   // 받기만 하고 대답하지 않음
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "redis-black-hole");
            acceptor.setDaemon(true);   // 테스트가 끝나면 같이 끝나게
            acceptor.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
