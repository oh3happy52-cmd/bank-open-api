package com.example.bank_open_api.usage.domain;

// [무엇] API 사용량 기록 엔티티 (DB의 api_usage_logs 테이블 한 줄)
// [역할] 오픈 API 요청 한 건의 "어느 앱·키가, 어떤 주소 틀을, 어떤 결과로, 얼마나 걸려, 언제" 를 담는다
//        한 번 저장하면 바꾸지 않는다 (setter 없음)
// [누가 쓰나] UsageLogRecorder(저장), ApiUsageLogRepository(통계 조회)
//
// 테이블은 Flyway(V4__create_api_usage_logs.sql)가 만들고, JPA는 ddl-auto=validate로 구조만 검사한다.

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "api_usage_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)   // JPA가 DB에서 읽어 올 때 쓰는 빈 생성자
public class ApiUsageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // App·ApiKey와 같은 이유로 객체 대신 번호만 들고 있음 (기록 저장 때 앱·키를 다시 읽지 않아도 됨)
    @Column(name = "app_id", nullable = false)
    private Long appId;

    @Column(name = "api_key_id", nullable = false)
    private Long apiKeyId;

    @Column(name = "method", nullable = false, length = 10)
    private String method;

    // 주소 틀 또는 UNMATCHED. 실제 주소(계좌번호 포함)는 절대 들어오지 않는다
    @Column(name = "endpoint", nullable = false, length = 200)
    private String endpoint;

    // SMALLINT 열 ↔ short (ddl-auto=validate가 타입을 비교하므로 맞춰 둠)
    @Column(name = "status_code", nullable = false)
    private short statusCode;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    // 한국 시간 기준 (Clock 빈의 시간대 Asia/Seoul)
    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    public ApiUsageLog(Long appId, Long apiKeyId, String method, String endpoint,
                       int statusCode, long latencyMs, LocalDateTime requestedAt) {
        this.appId = appId;
        this.apiKeyId = apiKeyId;
        this.method = method;
        this.endpoint = endpoint;
        this.statusCode = (short) statusCode;
        // INT 범위를 넘을 일은 사실상 없지만(약 24일), 넘으면 저장 실패 대신 최대값으로
        this.latencyMs = (int) Math.min(latencyMs, Integer.MAX_VALUE);
        this.requestedAt = requestedAt;
    }
}
