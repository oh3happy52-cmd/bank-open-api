package com.example.bank_open_api.openapi.api;

// [무엇] 오픈 API 연결 확인용 창구 (ping)
// [역할] API 키가 제대로 붙어 있는지 확인할 때 부른다. 인증된 키의 앱 번호와 키 앞 12글자를 돌려준다
// [누가 쓰나] 핀테크 앱 개발자(curl 등), 테스트
//            X-API-Key 헤더가 있어야 호출 가능 (OpenApiSecurityConfig + ApiKeyAuthenticationFilter)
//
// 예) curl -H "X-API-Key: boa_live_..." http://localhost:8081/open/v1/ping

import com.example.bank_open_api.common.security.ApiClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/open/v1")
public class PingController {

    /**
     * 연결 확인
     * GET /open/v1/ping
     * - 200: {appId, keyPrefix}
     * - 401: 키 없음·없는 키·폐기된 키 (모두 같은 INVALID_API_KEY 응답)
     */
    @GetMapping("/ping")
    public PingResponse ping(@AuthenticationPrincipal ApiClient client) {   // 검사원이 넣어 둔 호출자 정보
        return new PingResponse(client.appId(), client.keyPrefix());
    }

    // 응답 본문. 키 원문·해시는 넣지 않는다
    public record PingResponse(Long appId, String keyPrefix) {
    }
}
