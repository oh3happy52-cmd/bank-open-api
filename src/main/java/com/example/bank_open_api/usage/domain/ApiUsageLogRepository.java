package com.example.bank_open_api.usage.domain;

// [무엇] API 사용량 기록 저장소 (api_usage_logs 테이블 읽기·쓰기)
// [역할] 저장(save)은 JPA 기본 기능, 통계는 DB가 직접 세게(GROUP BY) 한다
//        → 기록이 수십만 건이어도 Java로 전부 가져오지 않고, 날짜 수(최대 31줄)·주소 틀 수만큼만 가져온다
// [누가 쓰나] UsageLogRecorder(저장), UsageStatsService(통계)
//
// ── 기간 조건을 "requested_at >= 시작 AND requested_at < 끝" 으로 쓰는 이유 ──
//   DATE(requested_at) BETWEEN ... 처럼 열에 함수를 씌우면 인덱스(app_id, requested_at)를 범위 검색에 못 쓴다.
//   그래서 "시작일 00:00 이상, 종료일 다음 날 00:00 미만" 으로 열을 그대로 비교한다 (끝을 포함하지 않는 반열린 구간)
//   → 23:59:59.999에 들어온 요청도 빠지지 않고, 다음 날 00:00:00 요청은 섞이지 않는다

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ApiUsageLogRepository extends JpaRepository<ApiUsageLog, Long> {

    // 날짜별 집계. cast(... as LocalDate)는 Hibernate가 MySQL의 DATE(...)로 바꿔 준다
    // 기록이 없는 날은 줄이 아예 없다 → UsageStatsService가 0으로 채운다
    @Query("""
            select new com.example.bank_open_api.usage.domain.UsageCounts$Daily(
                cast(l.requestedAt as LocalDate),
                count(l),
                sum(case when l.statusCode between 200 and 299 then 1 else 0 end),
                sum(case when l.statusCode between 400 and 499 and l.statusCode <> 429 then 1 else 0 end),
                sum(case when l.statusCode = 429 then 1 else 0 end),
                sum(case when l.statusCode between 500 and 599 then 1 else 0 end))
            from ApiUsageLog l
            where l.appId = :appId and l.requestedAt >= :start and l.requestedAt < :end
            group by cast(l.requestedAt as LocalDate)
            """)
    List<UsageCounts.Daily> countDaily(@Param("appId") Long appId,
                                       @Param("start") LocalDateTime start,
                                       @Param("end") LocalDateTime end);

    // 주소 틀별 집계 (기간 전체를 합친 값). 많이 불린 주소부터
    @Query("""
            select new com.example.bank_open_api.usage.domain.UsageCounts$ByEndpoint(
                l.endpoint,
                count(l),
                sum(case when l.statusCode between 200 and 299 then 1 else 0 end),
                sum(case when l.statusCode between 400 and 499 and l.statusCode <> 429 then 1 else 0 end),
                sum(case when l.statusCode = 429 then 1 else 0 end),
                sum(case when l.statusCode between 500 and 599 then 1 else 0 end))
            from ApiUsageLog l
            where l.appId = :appId and l.requestedAt >= :start and l.requestedAt < :end
            group by l.endpoint
            order by count(l) desc, l.endpoint asc
            """)
    List<UsageCounts.ByEndpoint> countByEndpoint(@Param("appId") Long appId,
                                                 @Param("start") LocalDateTime start,
                                                 @Param("end") LocalDateTime end);

    // 테스트·운영 확인용: 앱의 기록 전부 (최근 것부터)
    List<ApiUsageLog> findAllByAppIdOrderByIdDesc(Long appId);
}
