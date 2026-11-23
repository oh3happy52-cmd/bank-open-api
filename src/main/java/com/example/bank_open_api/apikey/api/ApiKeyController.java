package com.example.bank_open_api.apikey.api;

// [무엇] API 키 API 창구
// [역할] 내 앱의 API 키 발급·목록·폐기 요청을 받아 ApiKeyService에 넘김
// [누가 쓰나] 개발자 포털(프런트엔드), curl
//            모두 "Authorization: Bearer 토큰"(JWT)이 있어야 호출 가능 (SecurityConfig: anyRequest().authenticated())
//            ※ 여기서 발급한 API 키로 이 주소들을 부를 수는 없다. API 키는 나중에 /open/v1/** 에서만 쓴다
//
// 앱 주인은 문지기가 검사를 마친 토큰(Jwt)에서 LoginDeveloper로 꺼내서 넘긴다.

import com.example.bank_open_api.apikey.application.ApiKeyService;
import com.example.bank_open_api.common.security.LoginDeveloper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/apps/{appId}/keys")
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    /**
     * API 키 발급
     * POST /api/v1/apps/{appId}/keys
     * - 201: 발급 성공. 본문에 키 원문(apiKey)이 이번 한 번만 들어 있다
     * - 404: 없는 앱이거나 남의 앱
     * - 409: 이 앱의 ACTIVE 키가 이미 2개
     *
     * Cache-Control: no-store → 키 원문이 담긴 응답을 브라우저·프록시가 캐시에 저장하지 않게 한다
     * (Spring Security도 기본으로 비슷한 헤더를 붙이지만, 그 설정이 바뀌어도 이 응답만큼은 확실히 지키도록 직접 붙임)
     */
    @PostMapping
    public ResponseEntity<IssuedApiKeyResponse> issue(@PathVariable Long appId, @AuthenticationPrincipal Jwt jwt) {
        var issued = apiKeyService.issue(LoginDeveloper.from(jwt), appId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(IssuedApiKeyResponse.from(issued));
    }

    /**
     * API 키 목록 (최근 발급순)
     * GET /api/v1/apps/{appId}/keys
     * - 200: JSON 배열 (id, keyPrefix, status, createdAt, revokedAt). 원문·해시는 없음
     * - 404: 없는 앱이거나 남의 앱
     */
    @GetMapping
    public List<ApiKeyResponse> list(@PathVariable Long appId, @AuthenticationPrincipal Jwt jwt) {
        return apiKeyService.findKeys(LoginDeveloper.from(jwt), appId).stream()
                .map(ApiKeyResponse::from)
                .toList();
    }

    /**
     * API 키 폐기
     * DELETE /api/v1/apps/{appId}/keys/{keyId}
     * - 204: 폐기됨 (이미 폐기된 키를 다시 폐기해도 204. 처음 폐기 시각은 바뀌지 않음)
     * - 404: 없는 앱·남의 앱(APP_NOT_FOUND), 내 앱에 없는 키(API_KEY_NOT_FOUND)
     */
    @DeleteMapping("/{keyId}")
    public ResponseEntity<Void> revoke(@PathVariable Long appId, @PathVariable Long keyId,
                                       @AuthenticationPrincipal Jwt jwt) {
        apiKeyService.revoke(LoginDeveloper.from(jwt), appId, keyId);
        return ResponseEntity.noContent().build();
    }
}
