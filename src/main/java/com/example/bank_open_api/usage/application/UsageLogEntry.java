package com.example.bank_open_api.usage.application;

// [무엇] 사용량 기록 한 건 (다른 스레드로 넘기는 값 묶음)
// [역할] 요청 스레드가 응답을 마친 뒤 필요한 값만 골라 담아 저장 스레드에 넘긴다
//
// ── 왜 HttpServletRequest를 그대로 넘기지 않나? ──
//   request·response 객체는 요청이 끝나면 서버(Tomcat)가 다음 요청에 "재활용"한다.
//   다른 스레드가 나중에 그 객체를 읽으면 엉뚱한(다음 요청의) 값을 읽거나 오류가 날 수 있다.
//   그래서 요청이 살아 있는 동안 값만 꺼내, 바뀌지 않는 record(불변 객체)에 담아 넘긴다.
//   칸이 전부 바뀔 수 없는 타입(Long, String, int, long, LocalDateTime)이라 여러 스레드가 봐도 안전하다.
//
// [누가 쓰나] UsageLogFilter(만듦), UsageLogRecorder(저장 스레드에서 엔티티로 바꿔 저장)

import com.example.bank_open_api.usage.domain.ApiUsageLog;

import java.time.LocalDateTime;

public record UsageLogEntry(
        Long appId,                 // 앱 번호
        Long apiKeyId,              // 키 번호
        String method,              // GET 등
        String endpoint,            // 주소 틀 또는 UNMATCHED (실제 주소 아님)
        int statusCode,             // 응답 상태 코드 (예외가 밖으로 나오면 500)
        long latencyMs,             // 걸린 시간 (밀리초)
        LocalDateTime requestedAt   // 요청 시각 (한국 시간)
) {

    public ApiUsageLog toEntity() {
        return new ApiUsageLog(appId, apiKeyId, method, endpoint, statusCode, latencyMs, requestedAt);
    }
}
