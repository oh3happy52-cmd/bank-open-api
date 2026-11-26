package com.example.bank_open_api.usage.api;

// [무엇] 사용량 통계 창구 (개발자 포털, JWT 인증)
// [역할] 로그인한 개발자가 자기 앱의 오픈 API 사용량을 날짜별·주소 틀별로 본다
// [누가 쓰나] 개발자 포털(나중에 React 화면), curl
//
// 예) curl -H "Authorization: Bearer $TOKEN" "http://localhost:8081/api/v1/apps/1/usage?from=2026-11-20&to=2026-11-26"

import com.example.bank_open_api.common.security.LoginDeveloper;
import com.example.bank_open_api.usage.application.UsageStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/apps/{appId}/usage")
@RequiredArgsConstructor
public class UsageController {

    private final UsageStatsService usageStatsService;

    /**
     * 사용량 통계
     * GET /api/v1/apps/{appId}/usage?from=yyyy-MM-dd&to=yyyy-MM-dd
     * - 200: daily(from~to 모든 날짜, 기록 없는 날은 0) + byEndpoint(주소 틀별 합계)
     * - 400: from > to, 기간 31일 초과(INVALID_DATE_RANGE), 날짜 형식 오류(INVALID_REQUEST)
     * - 404: 없는 앱이거나 남의 앱
     * from·to를 생략하면 오늘(한국 시간) 포함 최근 7일
     */
    @GetMapping
    public UsageResponse usage(@PathVariable Long appId,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                               @AuthenticationPrincipal Jwt jwt) {
        return UsageResponse.from(usageStatsService.getUsage(LoginDeveloper.from(jwt), appId, from, to));
    }
}
