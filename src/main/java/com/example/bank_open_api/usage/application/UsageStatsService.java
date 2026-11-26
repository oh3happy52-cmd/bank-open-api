package com.example.bank_open_api.usage.application;

// [무엇] 사용량 통계 서비스
// [역할] 내 앱의 기간별 사용량을 날짜별(daily)·주소 틀별(byEndpoint)로 모아 돌려준다
//        ① 내 앱인지 확인 (없거나 남의 앱이면 404 — AppService.findMyApp, 1주차와 같은 원칙)
//        ② 기간 정하기: 생략하면 오늘 포함 최근 7일. from > to 이거나 31일 초과면 400
//        ③ DB가 GROUP BY로 센 결과를 받아, 기록이 없는 날은 0으로 채워 from~to 날짜가 빠짐없이 나오게 한다
// [누가 쓰나] UsageController (GET /api/v1/apps/{appId}/usage)
//
// ── 날짜는 모두 한국 시간 기준 ──
//   기록(requested_at)이 한국 시간으로 저장되고, "오늘"도 Clock(Asia/Seoul)로 계산한다.
//   예) 11-26 00:30 (한국) 요청은 UTC로는 11-25지만 11-26 칸에 들어간다

import com.example.bank_open_api.app.application.AppService;
import com.example.bank_open_api.common.exception.BusinessException;
import com.example.bank_open_api.common.exception.ErrorCode;
import com.example.bank_open_api.common.security.LoginDeveloper;
import com.example.bank_open_api.usage.domain.ApiUsageLogRepository;
import com.example.bank_open_api.usage.domain.UsageCounts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UsageStatsService {

    // 기간을 생략했을 때 보여 줄 날 수 (오늘 포함)
    static final int DEFAULT_DAYS = 7;
    // 한 번에 조회할 수 있는 최대 날 수 (from, to 포함)
    static final int MAX_DAYS = 31;

    private final AppService appService;
    private final ApiUsageLogRepository usageLogRepository;
    private final Clock clock;

    // 결과 묶음
    public record UsageStats(Long appId, LocalDate from, LocalDate to,
                             List<UsageCounts.Daily> daily, List<UsageCounts.ByEndpoint> byEndpoint) {
    }

    /**
     * @param from 시작일 (포함). null이면 to에서 6일 전
     * @param to   종료일 (포함). null이면 오늘(한국 시간)
     */
    @Transactional(readOnly = true)
    public UsageStats getUsage(LoginDeveloper loginDeveloper, Long appId, LocalDate from, LocalDate to) {
        // ① 내 앱이 아니거나 없으면 여기서 404 (기간 검사보다 먼저 → 남의 앱 번호로는 400도 보이지 않음)
        appService.findMyApp(loginDeveloper, appId);

        // ② 기간 정하기
        LocalDate end = (to != null) ? to : LocalDate.now(clock);
        LocalDate start = (from != null) ? from : end.minusDays(DEFAULT_DAYS - 1);
        if (start.isAfter(end)) {
            throw new BusinessException(ErrorCode.INVALID_DATE_RANGE);
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;   // 양 끝 포함
        if (days > MAX_DAYS) {
            throw new BusinessException(ErrorCode.INVALID_DATE_RANGE);
        }

        // DB 조회 범위: start 00:00 이상, (end 다음 날) 00:00 미만
        LocalDateTime rangeStart = start.atStartOfDay();
        LocalDateTime rangeEnd = end.plusDays(1).atStartOfDay();

        // ③ 날짜별: 기록이 있는 날만 오므로, 빈 날은 0으로 채운다
        Map<LocalDate, UsageCounts.Daily> counted = usageLogRepository.countDaily(appId, rangeStart, rangeEnd)
                .stream()
                .collect(Collectors.toMap(UsageCounts.Daily::date, Function.identity()));
        List<UsageCounts.Daily> daily = new ArrayList<>((int) days);
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            daily.add(counted.getOrDefault(date, new UsageCounts.Daily(date, 0L, 0L, 0L, 0L, 0L)));
        }

        // 주소 틀별: 기록이 있는 주소만 (많이 불린 순)
        List<UsageCounts.ByEndpoint> byEndpoint = usageLogRepository.countByEndpoint(appId, rangeStart, rangeEnd);

        return new UsageStats(appId, start, end, daily, byEndpoint);
    }
}
