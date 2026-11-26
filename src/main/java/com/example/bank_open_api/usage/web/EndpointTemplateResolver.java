package com.example.bank_open_api.usage.web;

// [무엇] 요청 주소 → "주소 틀" 찾기 도구
// [역할] 실제 주소를 컨트롤러에 적힌 주소 틀로 바꾼다. 계좌번호 같은 실제 값이 기록에 남지 않게 하기 위해서다
//          GET /open/v1/accounts/123-4567890  → /open/v1/accounts/{accountNumber}
//          GET /open/v1/ping                  → /open/v1/ping
//          GET /open/v1/nothing, DELETE /open/v1/ping (맞는 주소·메서드 없음) → UNMATCHED
// [누가 쓰나] UsageLogFilter (요청이 끝난 뒤 finally에서 한 번)
//
// ── 429처럼 컨트롤러까지 가지 않은 요청도 주소 틀을 아는 방법 ──
//   보통 Spring MVC는 DispatcherServlet이 컨트롤러를 고를 때 요청에 주소 틀(BEST_MATCHING_PATTERN)을 적어 둔다.
//   하지만 RateLimitFilter가 429로 끝낸 요청은 DispatcherServlet까지 가지 않아 그 표시가 없다.
//   그래서 Spring MVC가 이미 만들어 둔 "주소 목록표"(RequestMappingHandlerMapping.getHandlerMethods())에서
//   /open/v1 로 시작하는 주소 틀과 HTTP 메서드를 한 번만 꺼내 두고, 요청이 오면 직접 맞춰 본다.
//   - 맞춰 볼 때는 Spring MVC와 같은 PathPattern 규칙을 쓴다 → 컨트롤러가 받는 주소와 결과가 같다
//   - 여러 틀이 맞으면 Spring MVC처럼 "더 구체적인 틀"(SPECIFICITY_COMPARATOR)을 고른다
//   - 컨트롤러에 주소를 새로 추가하면 자동으로 목록에 들어온다 (주소를 따로 적어 둘 필요 없음)
//   요청 결과(200이든 429든)와 상관없이 항상 같은 방법으로 찾으므로, 같은 주소는 늘 같은 틀로 기록된다.
//
// ── 왜 처음 요청 때 목록을 만드나(lazy)? ──
//   필터 규칙표(OpenApiSecurityConfig)가 만들어질 때는 Spring MVC 주소 목록이 아직 준비되지 않았을 수 있다.
//   그래서 첫 요청 때 만들고, 그 뒤로는 만든 것을 계속 쓴다.

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Component
public class EndpointTemplateResolver {

    public static final String UNMATCHED = "UNMATCHED";

    // 이 앞부분으로 시작하는 주소 틀만 목록에 넣는다
    private static final String OPEN_API_PREFIX = "/open/v1";

    // Spring MVC가 만드는 주소 목록표 빈의 이름
    private static final String HANDLER_MAPPING_BEAN = "requestMappingHandlerMapping";

    // 주소 틀 하나: 받는 HTTP 메서드들(비어 있으면 전부) + 틀
    private record Route(Set<RequestMethod> methods, PathPattern pattern) {
        boolean accepts(RequestMethod method) {
            // HEAD는 Spring MVC가 GET 주소로 처리해 주므로 같게 본다
            return methods.isEmpty() || methods.contains(method)
                    || (method == RequestMethod.HEAD && methods.contains(RequestMethod.GET));
        }
    }

    private final ApplicationContext applicationContext;

    // 첫 요청 때 한 번 만든다. volatile: 다른 스레드도 완성된 목록을 바로 보게
    private volatile List<Route> routes;

    public EndpointTemplateResolver(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * @return 주소 틀(예: /open/v1/accounts/{accountNumber}) 또는 UNMATCHED. null을 돌려주지 않는다
     */
    public String resolve(HttpServletRequest request) {
        RequestMethod method = RequestMethod.resolve(request.getMethod());
        if (method == null) {
            return UNMATCHED;
        }
        // 서버 주소 앞부분(context path)을 뺀 경로. 지금은 context path가 비어 있다
        String path = request.getRequestURI().substring(request.getContextPath().length());
        PathContainer pathContainer = PathContainer.parsePath(path);

        return routes().stream()
                .filter(route -> route.accepts(method) && route.pattern().matches(pathContainer))
                .map(Route::pattern)
                .min(PathPattern.SPECIFICITY_COMPARATOR)   // 가장 구체적인 틀
                .map(PathPattern::getPatternString)
                .orElse(UNMATCHED);
    }

    private List<Route> routes() {
        List<Route> current = routes;
        if (current == null) {
            synchronized (this) {
                if (routes == null) {
                    routes = loadRoutes();
                }
                current = routes;
            }
        }
        return current;
    }

    private List<Route> loadRoutes() {
        RequestMappingHandlerMapping mapping =
                applicationContext.getBean(HANDLER_MAPPING_BEAN, RequestMappingHandlerMapping.class);
        List<Route> loaded = new ArrayList<>();
        for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
            PathPatternsRequestCondition patterns = info.getPathPatternsCondition();
            if (patterns == null) {
                continue;   // 옛 방식(AntPathMatcher) 설정이면 없음. 이 프로젝트는 기본값(PathPattern)을 쓴다
            }
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (PathPattern pattern : patterns.getPatterns()) {
                if (pattern.getPatternString().startsWith(OPEN_API_PREFIX)) {
                    loaded.add(new Route(methods, pattern));
                }
            }
        }
        loaded.sort(Comparator.comparing(route -> route.pattern().getPatternString()));
        return List.copyOf(loaded);
    }
}
