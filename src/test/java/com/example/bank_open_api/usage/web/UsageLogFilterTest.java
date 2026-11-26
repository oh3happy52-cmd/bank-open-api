package com.example.bank_open_api.usage.web;

// [무엇] UsageLogFilter 단위 테스트 (스프링·DB 없이 필터만)
// [역할] 진짜 요청으로 만들기 어려운 경우를 직접 만든다
//        - 뒤에서 예외가 밖으로 나오면 → 500으로 기록하고, 예외는 그대로 다시 던진다
//        - 뒤에서 정한 상태 코드(예: 404)를 그대로 기록
//        - 인증 정보(ApiClient)가 없으면 기록하지 않는다
//        - 주소 틀 찾기가 실패해도 응답(다음 단계 진행)에는 영향이 없다
// [누가 쓰나] ./gradlew test (Docker 필요 없음)

import com.example.bank_open_api.common.security.ApiClient;
import com.example.bank_open_api.usage.application.UsageLogEntry;
import com.example.bank_open_api.usage.application.UsageLogRecorder;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UsageLogFilterTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 26, 10, 0);

    UsageLogRecorder recorder;
    EndpointTemplateResolver resolver;
    UsageLogFilter filter;

    MockHttpServletRequest request;
    MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        recorder = mock(UsageLogRecorder.class);
        resolver = mock(EndpointTemplateResolver.class);
        when(resolver.resolve(any())).thenReturn("/open/v1/ping");
        Clock fixed = Clock.fixed(NOW.atZone(SEOUL).toInstant(), SEOUL);
        filter = new UsageLogFilter(recorder, resolver, fixed);

        request = new MockHttpServletRequest("GET", "/open/v1/ping");
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 뒤에서_예외가_나오면_500으로_기록하고_예외는_그대로_던진다() {
        authenticate(7L, 70L);
        FilterChain throwing = (req, res) -> {
            throw new IllegalStateException("뒤에서 터짐");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, throwing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("뒤에서 터짐");

        UsageLogEntry entry = captured();
        assertThat(entry.statusCode()).isEqualTo(500);   // 응답 객체는 아직 200이지만 500으로 기록
        assertThat(entry.appId()).isEqualTo(7L);
        assertThat(entry.apiKeyId()).isEqualTo(70L);
    }

    @Test
    void 뒤에서_정한_상태_코드와_요청_시각을_그대로_기록한다() throws Exception {
        authenticate(7L, 70L);
        FilterChain notFound = (req, res) -> response.setStatus(404);

        filter.doFilter(request, response, notFound);

        UsageLogEntry entry = captured();
        assertThat(entry.statusCode()).isEqualTo(404);
        assertThat(entry.method()).isEqualTo("GET");
        assertThat(entry.endpoint()).isEqualTo("/open/v1/ping");
        assertThat(entry.requestedAt()).isEqualTo(NOW);
        assertThat(entry.latencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void 인증_정보가_없으면_기록하지_않고_다음으로_넘긴다() throws Exception {
        boolean[] called = {false};
        filter.doFilter(request, response, (req, res) -> called[0] = true);

        assertThat(called[0]).isTrue();
        verify(recorder, never()).record(any());
    }

    @Test
    void 주소_틀_찾기가_실패해도_응답에는_영향이_없다() throws Exception {
        authenticate(7L, 70L);
        when(resolver.resolve(any())).thenThrow(new IllegalStateException("주소 목록 오류"));
        FilterChain ok = (req, res) -> response.setStatus(200);

        filter.doFilter(request, response, ok);   // 예외가 밖으로 나오지 않음

        assertThat(response.getStatus()).isEqualTo(200);
        verify(recorder, never()).record(any());
    }

    private void authenticate(Long appId, Long keyId) {
        ApiClient client = new ApiClient(appId, keyId, "boa_live_Xy3");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(client, null, List.of()));
    }

    private UsageLogEntry captured() {
        ArgumentCaptor<UsageLogEntry> captor = ArgumentCaptor.forClass(UsageLogEntry.class);
        verify(recorder).record(captor.capture());
        return captor.getValue();
    }
}
