package com.example.bank_open_api.usage.domain;

// [무엇] 사용량 집계 결과 (DB에서 GROUP BY로 센 숫자 묶음) — 날짜별 한 줄, 주소 틀별 한 줄
// [역할] ApiUsageLogRepository의 집계 쿼리가 "select new ..."로 바로 이 record를 만든다
// [누가 쓰나] ApiUsageLogRepository(쿼리 결과), UsageStatsService(응답 만들기)
//
// 분류 기준 (상태 코드)
//   success     : 200~299
//   clientError : 400~499 중 429 제외 (요청을 잘못 보냄: 404 계좌 없음, 400 형식 오류 등)
//   rateLimited : 429 (호출 한도 초과) — 4xx지만 "너무 많이 부름"이라 원인이 달라서 따로 센다
//   serverError : 500~599 (우리 또는 bank_core 쪽 문제: 500, 502, 504)
//   total       : 위 넷의 합이 아니라 전체 건수 (1xx·3xx 같은 예외적인 코드도 포함)
//
// 숫자 칸이 long이 아니라 Long인 이유: JPQL의 count()·sum() 결과가 Long이라 생성자 타입을 그대로 맞춤

import java.time.LocalDate;

public final class UsageCounts {

    private UsageCounts() {
    }

    // 날짜별 (한국 시간 기준 날짜)
    public record Daily(LocalDate date, Long total, Long success, Long clientError,
                        Long rateLimited, Long serverError) {
    }

    // 주소 틀별 (예: /open/v1/accounts/{accountNumber}, UNMATCHED)
    public record ByEndpoint(String endpoint, Long total, Long success, Long clientError,
                             Long rateLimited, Long serverError) {
    }
}
