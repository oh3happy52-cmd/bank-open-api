package com.example.bank_open_api.usage.api;

// [무엇] 사용량 통계 응답 본문(JSON)
// [역할] 서비스 결과를 응답 모양으로 바꾼다
//        {
//          "appId": 1, "from": "2026-11-20", "to": "2026-11-26",
//          "daily":      [{"date": "2026-11-20", "total": 3, "success": 2, "clientError": 0, "rateLimited": 1, "serverError": 0}, ...],
//          "byEndpoint": [{"endpoint": "/open/v1/accounts/{accountNumber}", "total": 3, "success": 2, ...}, ...]
//        }
// [누가 쓰나] UsageController

import com.example.bank_open_api.usage.application.UsageStatsService.UsageStats;
import com.example.bank_open_api.usage.domain.UsageCounts;

import java.time.LocalDate;
import java.util.List;

public record UsageResponse(
        Long appId,
        LocalDate from,
        LocalDate to,
        List<Daily> daily,
        List<ByEndpoint> byEndpoint
) {

    public record Daily(LocalDate date, long total, long success, long clientError,
                        long rateLimited, long serverError) {
        static Daily from(UsageCounts.Daily row) {
            return new Daily(row.date(), row.total(), row.success(), row.clientError(),
                    row.rateLimited(), row.serverError());
        }
    }

    public record ByEndpoint(String endpoint, long total, long success, long clientError,
                             long rateLimited, long serverError) {
        static ByEndpoint from(UsageCounts.ByEndpoint row) {
            return new ByEndpoint(row.endpoint(), row.total(), row.success(), row.clientError(),
                    row.rateLimited(), row.serverError());
        }
    }

    public static UsageResponse from(UsageStats stats) {
        return new UsageResponse(
                stats.appId(), stats.from(), stats.to(),
                stats.daily().stream().map(Daily::from).toList(),
                stats.byEndpoint().stream().map(ByEndpoint::from).toList());
    }
}
