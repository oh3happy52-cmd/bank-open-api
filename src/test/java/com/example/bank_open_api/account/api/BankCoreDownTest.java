package com.example.bank_open_api.account.api;

// [무엇] bank_core가 꺼져 있을 때의 계좌 조회 테스트
// [역할] 아무도 듣고 있지 않은 포트를 bank_core 주소로 넣어 "연결 거부"를 진짜로 만들고,
//        /open/v1/accounts/{번호}가 502 BANK_CORE_UNAVAILABLE을 주는지 확인
//        (bank_core 주소가 다른 테스트와 달라야 해서 BankCoreAccountTest와 클래스를 나눔)
// [누가 쓰나] ./gradlew test 실행 시 JUnit이 실행 (Docker 필요: Testcontainers MySQL)

import com.example.bank_open_api.TestcontainersConfiguration;
import com.example.bank_open_api.support.OpenApiFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "jwt.secret=test-only-jwt-secret-key-0123456789abcdef",
        "bank-core.username=it-open-api",
        "bank-core.password=it-fake-password-7Qz"
})
@Import(TestcontainersConfiguration.class)
class BankCoreDownTest {

    // 빈 포트를 하나 잡았다가 바로 닫음 → 그 포트로 접속하면 "연결 거부" (= bank_core가 꺼져 있는 상황)
    private static final int CLOSED_PORT = findClosedPort();

    @DynamicPropertySource
    static void bankCoreProperties(DynamicPropertyRegistry registry) {
        registry.add("bank-core.base-url", () -> "http://127.0.0.1:" + CLOSED_PORT);
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
    void bank_core가_꺼져_있으면_502_BANK_CORE_UNAVAILABLE() throws Exception {
        OpenApiFixture.Issued issued = OpenApiFixture.issueApiKey(mockMvc);

        // 첫 요청이라 bank_core 로그인부터 시도 → 연결 거부 → 502
        mockMvc.perform(get("/open/v1/accounts/{accountNumber}", "100-0001")
                        .header("X-API-Key", issued.apiKey()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("BANK_CORE_UNAVAILABLE"));
    }

    private static int findClosedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
