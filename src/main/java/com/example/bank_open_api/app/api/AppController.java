package com.example.bank_open_api.app.api;

// [무엇] 앱 API 창구
// [역할] 앱 등록·내 앱 목록·내 앱 상세 요청을 받아 AppService에 넘김
// [누가 쓰나] 개발자 포털(프런트엔드), curl
//            모두 "Authorization: Bearer 토큰"이 있어야 호출 가능 (SecurityConfig: anyRequest().authenticated())
//
// 앱 주인은 문지기가 검사를 마친 토큰(Jwt)에서 LoginDeveloper로 꺼내서 넘긴다. 요청 본문에서 받지 않는다.

import com.example.bank_open_api.app.application.AppService;
import com.example.bank_open_api.common.security.LoginDeveloper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/apps")
@RequiredArgsConstructor
public class AppController {

    private final AppService appService;

    /**
     * 앱 등록
     * POST /api/v1/apps
     * - 201: 등록 성공 (본문: 등록한 앱 정보)
     * - 400: 입력 오류 (이름 없음·50자 초과, 설명 200자 초과)
     * - 409: 내 앱 중에 같은 이름이 이미 있음
     * - 401: 토큰 없음·가짜·만료
     */
    @PostMapping
    public ResponseEntity<AppResponse> create(@Valid @RequestBody CreateAppRequest request,
                                              @AuthenticationPrincipal Jwt jwt) {
        var app = appService.create(LoginDeveloper.from(jwt), request.name(), request.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(AppResponse.from(app));
    }

    /**
     * 내 앱 목록 (최근 등록순)
     * GET /api/v1/apps
     * - 200: JSON 배열. 앱이 없으면 빈 배열 []
     */
    @GetMapping
    public List<AppResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return appService.findMyApps(LoginDeveloper.from(jwt)).stream()
                .map(AppResponse::from)
                .toList();
    }

    /**
     * 내 앱 상세
     * GET /api/v1/apps/{appId}
     * - 200: 앱 정보
     * - 404: 없는 앱이거나 남의 앱 (둘을 구분하지 않음. 이유는 AppService.findMyApp 주석 참고)
     * - 400: appId가 숫자가 아님
     */
    @GetMapping("/{appId}")
    public AppResponse detail(@PathVariable Long appId, @AuthenticationPrincipal Jwt jwt) {
        return AppResponse.from(appService.findMyApp(LoginDeveloper.from(jwt), appId));
    }
}
