package com.example.bank_open_api.ratelimit;

// [무엇] 호출 제한 "다시 채워짐" 테스트 — 진짜 Redis, 진짜 시간 흐름
// [역할] 양동이를 다 쓰면 429 → 잠깐 기다리면 토큰이 다시 채워져 통과하는지 확인
// [누가 쓰나] ./gradlew test 실행 시 JUnit이 실행 (Docker 필요: Testcontainers MySQL·Redis)
//
// ── 설정값을 바꾼 이유 ──
// 기본값(10개, 초당 1개)이면 다 쓰고 채워지기를 기다리느라 테스트가 느려진다.
// 여기서는 양동이 2개, 초당 10개(0.1초에 1개)로 줄여서 0.3초만 기다리면 되게 했다.
// (설정값이 달라야 해서 RateLimitTest와 클래스를 나눔 — 스프링 테스트 환경이 따로 뜬다)

import com.example.bank_open_api.TestcontainersConfiguration;
import com.example.bank_open_api.support.OpenApiFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "jwt.secret=test-only-jwt-secret-key-0123456789abcdef",
        "rate-limit.capacity=2",
        "rate-limit.refill-per-second=10"   // 0.1초에 1개
})
@Import(TestcontainersConfiguration.class)
class RateLimitRefillTest {

    private static final String PING = "/open/v1/ping";
    private static final String API_KEY_HEADER = "X-API-Key";

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
    void 다_쓰면_429이고_시간이_지나면_다시_채워져_통과한다() throws Exception {
        OpenApiFixture.Issued issued = OpenApiFixture.issueApiKey(mockMvc);

        // 2개 다 쓰기
        mockMvc.perform(get(PING).header(API_KEY_HEADER, issued.apiKey())).andExpect(status().isOk());
        mockMvc.perform(get(PING).header(API_KEY_HEADER, issued.apiKey())).andExpect(status().isOk());

        // 바로 보내면 429. 다음 토큰까지 0.1초 이하 → Retry-After는 올림해서 최소값 1초
        mockMvc.perform(get(PING).header(API_KEY_HEADER, issued.apiKey()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"));

        // 0.3초 기다리면 약 3개분이 채워짐 (양동이 크기 2를 넘지 않음)
        Thread.sleep(300);

        mockMvc.perform(get(PING).header(API_KEY_HEADER, issued.apiKey()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "2"))
                .andExpect(header().string("X-RateLimit-Remaining", "1"));   // 가득(2) 찼다가 1개 씀
    }
}
