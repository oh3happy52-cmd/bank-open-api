package com.example.bank_open_api.app.api;

// [무엇] 앱 등록·목록·상세 테스트 (HTTP 요청부터 DB까지 끝까지)
// [역할] MockMvc로 진짜 HTTP 요청처럼 보내서, 문지기(Spring Security) → Controller → Service → MySQL 전체를 확인
//        - 등록: 성공 201 / 입력 오류 400 / 같은 개발자 이름 중복 409 / 다른 개발자는 같은 이름 가능
//        - 주인: 본문에 developerId를 넣어도 무시되고 토큰 주인으로 등록됨
//        - 목록: 내 앱만, 최근 등록순
//        - 상세: 남의 앱 404 / 없는 앱 404 (두 응답이 완전히 같은지)
//        - 토큰 없음: 세 API 모두 401
// [누가 쓰나] ./gradlew test 실행 시 JUnit이 실행 (Docker가 켜져 있어야 함: Testcontainers MySQL)

import com.example.bank_open_api.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// jwt.secret: .env가 없는 곳(CI 등)에서도 돌아가도록 테스트 전용 키를 넣음 (진짜 키 아님)
@SpringBootTest(properties = "jwt.secret=test-only-jwt-secret-key-0123456789abcdef")
@Import(TestcontainersConfiguration.class)
class AppTest {

    @Autowired
    WebApplicationContext context;

    MockMvc mockMvc;   // 서버를 켜지 않고 HTTP 요청을 흉내 내는 도구

    // 테스트마다 새로 가입한 개발자 두 명의 토큰 (A = 나, B = 다른 개발자)
    String tokenA;
    String tokenB;

    @BeforeEach
    void setUp() throws Exception {
        // springSecurity(): 문지기(Spring Security)를 거치도록 설정 → 실제 서버와 같은 순서로 검사됨
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        tokenA = signupAndLogin();
        tokenB = signupAndLogin();
    }

    // ───────────── 등록 ─────────────

    @Test
    void 앱_등록_성공하면_201과_앱_정보() throws Exception {
        createApp(tokenA, "결제 앱", "간편 결제 서비스")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("결제 앱"))
                .andExpect(jsonPath("$.description").value("간편 결제 서비스"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.developerId").doesNotExist());
    }

    @Test
    void 이름이_없거나_50자를_넘거나_설명이_200자를_넘으면_400() throws Exception {
        // 이름 빈값
        createApp(tokenA, "", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // 이름 51자
        createApp(tokenA, "a".repeat(51), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // 설명 201자
        createApp(tokenA, "앱", "a".repeat(201))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 같은_개발자가_같은_이름으로_등록하면_409() throws Exception {
        createApp(tokenA, "My App", null).andExpect(status().isCreated());

        createApp(tokenA, "My App", "설명만 다름")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_APP_NAME"));
    }

    @Test
    void 다른_개발자는_같은_이름으로_등록할_수_있다() throws Exception {
        createApp(tokenA, "My App", null).andExpect(status().isCreated());

        createApp(tokenB, "My App", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("My App"));
    }

    @Test
    void 본문에_developerId를_넣어도_무시되고_토큰_주인의_앱으로_등록된다() throws Exception {
        long developerIdA = myDeveloperId(tokenA);

        // B가 "A의 앱으로 만들어 줘"라고 본문에 developerId를 넣어 보냄
        String body = mockMvc.perform(post("/api/v1/apps")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "가로채기 시도", "developerId": %d}
                                """.formatted(developerIdA)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        int appId = JsonPath.read(body, "$.id");

        // A의 목록에는 없고, A가 조회하면 404 → A의 앱이 아니다
        mockMvc.perform(get("/api/v1/apps").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        getApp(tokenA, appId).andExpect(status().isNotFound());

        // B가 조회하면 보인다 → 토큰 주인(B)의 앱으로 등록됐다
        getApp(tokenB, appId).andExpect(status().isOk());
    }

    // ───────────── 목록 ─────────────

    @Test
    void 목록에는_내_앱만_최근_등록순으로_나온다() throws Exception {
        createApp(tokenA, "A의 첫 번째 앱", null).andExpect(status().isCreated());
        createApp(tokenB, "B의 앱", null).andExpect(status().isCreated());
        createApp(tokenA, "A의 두 번째 앱", null).andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/apps").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                // 나중에 등록한 앱이 먼저, B의 앱은 없음
                .andExpect(jsonPath("$[*].name", contains("A의 두 번째 앱", "A의 첫 번째 앱")));
    }

    // ───────────── 상세 ─────────────

    @Test
    void 남의_앱을_조회하면_404() throws Exception {
        int appIdOfA = createAppAndGetId(tokenA, "A의 앱");

        getApp(tokenB, appIdOfA)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APP_NOT_FOUND"));

        // 주인(A)은 정상 조회
        getApp(tokenA, appIdOfA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("A의 앱"));
    }

    @Test
    void 없는_앱_조회도_404이고_남의_앱_조회와_응답이_완전히_같다() throws Exception {
        int appIdOfA = createAppAndGetId(tokenA, "A의 앱");

        MockHttpServletResponse othersApp = getApp(tokenB, appIdOfA).andReturn().getResponse();
        MockHttpServletResponse notExists = getApp(tokenB, Long.MAX_VALUE).andReturn().getResponse();

        // 상태 코드와 본문(JSON 글자 하나하나)까지 같아야 → 응답만 보고는 그 번호의 앱이 있는지 알 수 없음
        assertThat(notExists.getStatus()).isEqualTo(404);
        assertThat(othersApp.getStatus()).isEqualTo(notExists.getStatus());
        assertThat(othersApp.getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(notExists.getContentAsString(StandardCharsets.UTF_8));
    }

    // ───────────── 토큰 없음 ─────────────

    @Test
    void 토큰이_없으면_세_API_모두_401() throws Exception {
        mockMvc.perform(post("/api/v1/apps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "앱"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(get("/api/v1/apps"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(get("/api/v1/apps/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // ───────────── 도우미 ─────────────

    // 새 개발자를 가입시키고 로그인해서 토큰을 돌려줌 (테스트끼리 섞이지 않게 매번 다른 이메일)
    private String signupAndLogin() throws Exception {
        String email = "dev-" + UUID.randomUUID() + "@example.com";
        String password = "password1234";

        mockMvc.perform(post("/api/v1/developers/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s", "name": "개발자"}
                                """.formatted(email, password)))
                .andExpect(status().isCreated());

        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.accessToken");
    }

    // 토큰 주인의 개발자 번호 (/me로 확인)
    private long myDeveloperId(String token) throws Exception {
        String body = mockMvc.perform(get("/api/v1/developers/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    // description이 null이면 JSON에서 아예 뺀다 (선택 항목)
    private ResultActions createApp(String token, String name, String description) throws Exception {
        String json = (description == null)
                ? """
                  {"name": "%s"}
                  """.formatted(name)
                : """
                  {"name": "%s", "description": "%s"}
                  """.formatted(name, description);
        return mockMvc.perform(post("/api/v1/apps")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private int createAppAndGetId(String token, String name) throws Exception {
        String body = createApp(token, name, null)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.id");
    }

    private ResultActions getApp(String token, long appId) throws Exception {
        return mockMvc.perform(get("/api/v1/apps/" + appId).header("Authorization", "Bearer " + token));
    }
}
