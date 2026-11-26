package com.example.bank_open_api.ratelimit;

// [무엇] Redis가 꺼져 있을 때의 호출 제한 테스트 (fail-open)
// [역할] 아무도 듣고 있지 않은 포트를 Redis 주소로 넣어 "연결 거부"를 진짜로 만들고,
//        /open/v1/** 요청이 막히지 않고 통과하는지, WARN 로그에 appId만 남고 키 원문은 없는지 확인
// [누가 쓰나] ./gradlew test 실행 시 JUnit이 실행 (Docker 필요: Testcontainers MySQL)
//
// ── 왜 TestcontainersConfiguration을 가져오지 않나? ──
// 그 설정은 Redis 컨테이너도 띄우고 @ServiceConnection으로 Redis 주소를 덮어쓴다.
// 여기서는 Redis가 "없어야" 하므로 MySQL 컨테이너만 있는 설정(아래 MySqlOnly)을 따로 쓴다.

import com.example.bank_open_api.support.OpenApiFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "jwt.secret=test-only-jwt-secret-key-0123456789abcdef",
        "rate-limit.capacity=1"   // 1개뿐이라, 제한이 살아 있었다면 두 번째 요청부터 429가 났을 것
})
@Import(RateLimitRedisDownTest.MySqlOnly.class)
@ExtendWith(OutputCaptureExtension.class)
class RateLimitRedisDownTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class MySqlOnly {
        @Bean
        @ServiceConnection
        MySQLContainer mysqlContainer() {
            return new MySQLContainer(DockerImageName.parse("mysql:8.4"));
        }
    }

    // 빈 포트를 잡았다가 바로 닫음 → 그 포트로 접속하면 "연결 거부" (= Redis가 꺼져 있는 상황)
    private static final int CLOSED_PORT = findClosedPort();

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> CLOSED_PORT);
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
    void Redis가_꺼져_있으면_제한_없이_통과하고_WARN_로그에는_appId만_남는다(CapturedOutput output) throws Exception {
        OpenApiFixture.Issued issued = OpenApiFixture.issueApiKey(mockMvc);

        // 양동이가 1개짜리지만 Redis를 못 쓰므로 3번 모두 통과. 모르는 값이라 제한 헤더는 붙이지 않음
        for (int i = 0; i < 3; i++) {
            long started = System.nanoTime();
            mockMvc.perform(get("/open/v1/ping").header("X-API-Key", issued.apiKey()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.appId").value(issued.appId()))
                    .andExpect(header().doesNotExist("X-RateLimit-Limit"))
                    .andExpect(header().doesNotExist("X-RateLimit-Remaining"));
            long tookMillis = (System.nanoTime() - started) / 1_000_000;
            // Redis 제한 시간(0.5초) 덕분에 오래 붙잡히지 않는다 (여유 있게 2초 이내로 확인)
            assertThat(tookMillis).isLessThan(2_000);
        }

        assertThat(output.getAll())
                .contains("fail-open")
                .contains("appId=" + issued.appId())
                .doesNotContain(issued.apiKey());
    }

    private static int findClosedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
