package com.example.bank_open_api.developer.api;

// [무엇] 개발자 회원가입·로그인·내 정보 테스트 (HTTP 요청부터 DB까지 끝까지)
// [역할] MockMvc로 진짜 HTTP 요청처럼 보내서, 문지기(Spring Security) → Controller → Service → MySQL 전체를 확인
//        - 회원가입: 성공 201 / 이메일 중복 409 / 입력 오류 400 / 비밀번호 72바이트 초과 400 (한글)
//        - 로그인: 성공 200 / 이메일 없음 401 / 비밀번호 틀림 401 / 두 실패 응답이 똑같은지
//        - /me: 토큰 있음 200 (비밀번호 해시 없음) / 토큰 없음 401
// [누가 쓰나] ./gradlew test 실행 시 JUnit이 실행 (Docker가 켜져 있어야 함: Testcontainers MySQL)

import com.example.bank_open_api.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// jwt.secret: .env가 없는 곳(CI 등)에서도 돌아가도록 테스트 전용 키를 넣음 (진짜 키 아님)
@SpringBootTest(properties = "jwt.secret=test-only-jwt-secret-key-0123456789abcdef")
@Import(TestcontainersConfiguration.class)
class DeveloperAuthTest {

    private static final String PASSWORD = "password1234";

    @Autowired
    WebApplicationContext context;

    MockMvc mockMvc;   // 서버를 켜지 않고 HTTP 요청을 흉내 내는 도구

    @BeforeEach
    void setUp() {
        // springSecurity(): 문지기(Spring Security)를 거치도록 설정 → 실제 서버와 같은 순서로 검사됨
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    // ───────────── 회원가입 ─────────────

    @Test
    void 회원가입_성공하면_201과_내_정보를_돌려주고_비밀번호_해시는_없다() throws Exception {
        String email = uniqueEmail();

        signup(email, PASSWORD, "홍길동")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.name").value("홍길동"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void 같은_이메일로_다시_가입하면_409() throws Exception {
        String email = uniqueEmail();
        signup(email, PASSWORD, "홍길동").andExpect(status().isCreated());

        // 대문자로 바꿔도 같은 이메일로 본다 (소문자로 정리해서 저장하므로)
        signup(email.toUpperCase(), PASSWORD, "김철수")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
    }

    @Test
    void 이메일_형식이_틀리거나_비밀번호가_8자_미만이면_400() throws Exception {
        // 이메일 형식 오류
        signup("not-an-email", PASSWORD, "홍길동")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // 비밀번호 7자
        signup(uniqueEmail(), "1234567", "홍길동")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 비밀번호는_글자_수가_아니라_UTF8_72바이트로_검사한다() throws Exception {
        // 한글 1글자 = UTF-8 3바이트
        String korean72Bytes = "가".repeat(24);   // 24글자 = 72바이트 → 통과 (경계값)
        String korean75Bytes = "가".repeat(25);   // 25글자 = 75바이트 → 글자 수는 72 미만이지만 실패해야 함
        assertThat(korean72Bytes.getBytes(StandardCharsets.UTF_8)).hasSize(72);
        assertThat(korean75Bytes.getBytes(StandardCharsets.UTF_8)).hasSize(75);

        signup(uniqueEmail(), korean72Bytes, "홍길동")
                .andExpect(status().isCreated());

        signup(uniqueEmail(), korean75Bytes, "홍길동")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // ───────────── 로그인 ─────────────

    @Test
    void 로그인_성공하면_200과_토큰() throws Exception {
        String email = uniqueEmail();
        signup(email, PASSWORD, "홍길동").andExpect(status().isCreated());

        login(email, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600));
    }

    @Test
    void 없는_이메일로_로그인하면_401() throws Exception {
        login(uniqueEmail(), PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void 비밀번호가_틀리면_401() throws Exception {
        String email = uniqueEmail();
        signup(email, PASSWORD, "홍길동").andExpect(status().isCreated());

        login(email, "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void 이메일_없음과_비밀번호_틀림의_응답은_완전히_같다() throws Exception {
        String email = uniqueEmail();
        signup(email, PASSWORD, "홍길동").andExpect(status().isCreated());

        var notFound = login(uniqueEmail(), PASSWORD).andReturn().getResponse();
        var wrongPassword = login(email, "wrong-password").andReturn().getResponse();

        // 상태 코드와 본문(JSON 글자 하나하나)까지 같아야 → 응답만 보고는 이메일 존재 여부를 알 수 없음
        assertThat(notFound.getStatus()).isEqualTo(401);
        assertThat(wrongPassword.getStatus()).isEqualTo(notFound.getStatus());
        assertThat(wrongPassword.getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(notFound.getContentAsString(StandardCharsets.UTF_8));
    }

    // ───────────── 내 정보 (/me) ─────────────

    @Test
    void 토큰이_있으면_내_정보를_돌려주고_비밀번호_해시는_없다() throws Exception {
        String email = uniqueEmail();
        signup(email, PASSWORD, "홍길동").andExpect(status().isCreated());
        String token = loginAndGetToken(email, PASSWORD);

        mockMvc.perform(get("/api/v1/developers/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.name").value("홍길동"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void 토큰이_없으면_401_JSON() throws Exception {
        mockMvc.perform(get("/api/v1/developers/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다"));
    }

    // ───────────── 도우미 ─────────────

    // 테스트끼리 같은 DB를 쓰므로, 매번 다른 이메일을 만들어 서로 영향을 주지 않게 함
    private static String uniqueEmail() {
        return "dev-" + UUID.randomUUID() + "@example.com";
    }

    private ResultActions signup(String email, String password, String name) throws Exception {
        return mockMvc.perform(post("/api/v1/developers/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s", "name": "%s"}
                        """.formatted(email, password, name)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(email, password)));
    }

    private String loginAndGetToken(String email, String password) throws Exception {
        String body = login(email, password)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.accessToken");
    }
}
