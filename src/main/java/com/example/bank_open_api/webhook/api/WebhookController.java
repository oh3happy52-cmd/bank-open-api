package com.example.bank_open_api.webhook.api;

// [무엇] 웹훅 설정 창구 (개발자 포털, JWT 인증)
// [역할] 내 앱의 웹훅 주소 등록·조회·끄기, 비밀키 교체, 전송 기록 조회
//        비밀키 원문이 담길 수 있는 응답(PUT, rotate)에는 Cache-Control: no-store
//        → 브라우저·프록시가 응답을 저장해 두지 않게 (1주차 API 키 발급과 같은 이유)
// [누가 쓰나] 개발자 포털(나중에 React 화면), curl
//
// 예) curl -X PUT -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
//       -d '{"url":"https://fintech.example.com/webhooks"}' http://localhost:8081/api/v1/apps/1/webhook

import com.example.bank_open_api.common.security.LoginDeveloper;
import com.example.bank_open_api.webhook.application.WebhookEndpointService;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/apps/{appId}/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookEndpointService webhookEndpointService;

    /**
     * 등록·수정
     * PUT /api/v1/apps/{appId}/webhook  {url}
     * - 201: 처음 등록. 본문에 secret(비밀키 원문)이 이번 한 번만 들어 있다
     * - 200: 주소 수정(꺼져 있었으면 다시 켬). secret 없음 (비밀키는 그대로)
     * - 400: INVALID_WEBHOOK_URL / INVALID_REQUEST(빈 값)
     * - 404: 없는 앱이거나 남의 앱
     */
    @PutMapping
    public ResponseEntity<WebhookResponse> upsert(@PathVariable Long appId,
                                                  @Valid @RequestBody WebhookUpsertRequest request,
                                                  @AuthenticationPrincipal Jwt jwt) {
        var result = webhookEndpointService.upsert(LoginDeveloper.from(jwt), appId, request.url());
        WebhookResponse body = result.created()
                ? WebhookResponse.withSecret(result.endpoint(), result.secret())
                : WebhookResponse.of(result.endpoint());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    /**
     * 조회
     * GET /api/v1/apps/{appId}/webhook
     * - 200: {url, secretPrefix, enabled}
     * - 404: APP_NOT_FOUND / WEBHOOK_NOT_FOUND(아직 등록 안 함)
     */
    @GetMapping
    public WebhookResponse get(@PathVariable Long appId, @AuthenticationPrincipal Jwt jwt) {
        return WebhookResponse.of(webhookEndpointService.get(LoginDeveloper.from(jwt), appId));
    }

    /**
     * 끄기
     * DELETE /api/v1/apps/{appId}/webhook
     * - 204: 꺼짐 (이미 꺼져 있어도 204). 설정·비밀키는 지우지 않는다 → PUT으로 다시 켤 수 있음
     * - 404: APP_NOT_FOUND / WEBHOOK_NOT_FOUND
     */
    @DeleteMapping
    public ResponseEntity<Void> disable(@PathVariable Long appId, @AuthenticationPrincipal Jwt jwt) {
        webhookEndpointService.disable(LoginDeveloper.from(jwt), appId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 비밀키 교체
     * POST /api/v1/apps/{appId}/webhook/secret/rotate
     * - 200: {secret, secretPrefix} — 새 비밀키 원문은 이번 한 번만. 옛 키는 바로 쓸 수 없게 된다
     * - 404: APP_NOT_FOUND / WEBHOOK_NOT_FOUND
     */
    @PostMapping("/secret/rotate")
    public ResponseEntity<WebhookSecretResponse> rotate(@PathVariable Long appId, @AuthenticationPrincipal Jwt jwt) {
        var rotated = webhookEndpointService.rotateSecret(LoginDeveloper.from(jwt), appId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new WebhookSecretResponse(rotated.secret(), rotated.secretPrefix()));
    }

    /**
     * 전송 기록 (최신순)
     * GET /api/v1/apps/{appId}/webhook/deliveries?page=0&size=20
     * - 200: {items, page, size, totalElements, totalPages}. size 기본 20, 최대 100 (넘으면 100)
     * - 400: page < 0 또는 size < 1
     * - 404: 없는 앱이거나 남의 앱 (웹훅을 등록하지 않았어도 내 앱이면 빈 목록)
     */
    @GetMapping("/deliveries")
    public WebhookDeliveryPageResponse deliveries(@PathVariable Long appId,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "" + WebhookEndpointService.DEFAULT_PAGE_SIZE) int size,
                                                  @AuthenticationPrincipal Jwt jwt) {
        return WebhookDeliveryPageResponse.from(
                webhookEndpointService.deliveries(LoginDeveloper.from(jwt), appId, page, size));
    }
}
