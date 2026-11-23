package com.example.bank_open_api.apikey.domain;

// [무엇] API 키 엔티티 (DB의 api_keys 테이블 한 줄)
// [역할] 어느 앱의 키인지, 화면 표시용 앞 12글자, 키 원문의 SHA-256 해시, 상태, 발급·폐기 시각을 들고 있음
//        키 원문은 이 클래스에 칸이 없다 → 실수로라도 DB에 저장될 수 없다
// [누가 쓰나] ApiKeyService(발급·목록·폐기), ApiKeyRepository
//
// 테이블은 Flyway(V3__create_api_keys.sql)가 만들고, JPA는 ddl-auto=validate로 구조만 검사한다.

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "api_keys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)   // JPA가 DB에서 읽어 올 때 쓰는 빈 생성자 (밖에서는 못 씀)
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)   // MySQL AUTO_INCREMENT
    private Long id;

    // 어느 앱의 키인가 (apps.id). App과 같은 이유로 객체 대신 번호만 들고 있음
    @Column(name = "app_id", nullable = false)
    private Long appId;

    // 화면 표시용 앞 12글자 (예: boa_live_Xy3). 원문 일부라 혼자서는 쓸 수 없고, 검색에도 쓰지 않음
    @Column(name = "key_prefix", nullable = false, length = 12)
    private String keyPrefix;

    // 키 원문의 SHA-256 해시 (16진수 64글자). 응답(ApiKeyResponse)에는 절대 넣지 않는다
    @Column(name = "key_hash", nullable = false, unique = true, length = 64)
    private String keyHash;

    // @JdbcTypeCode(VARCHAR): Hibernate가 MySQL에서 enum을 ENUM 타입 열로 기대하지 않고
    // VARCHAR 열(V3 SQL)과 맞춰 보게 함 (bank_core Member.role과 같은 방식)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 10)
    private ApiKeyStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // ACTIVE면 null
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    private ApiKey(Long appId, String keyPrefix, String keyHash, LocalDateTime createdAt) {
        this.appId = appId;
        this.keyPrefix = keyPrefix;
        this.keyHash = keyHash;
        this.status = ApiKeyStatus.ACTIVE;
        this.createdAt = createdAt;
    }

    // 새 키 만들기 (발급). 원문이 아니라 해시와 앞 12글자만 받는다
    public static ApiKey issue(Long appId, String keyPrefix, String keyHash) {
        return new ApiKey(appId, keyPrefix, keyHash, LocalDateTime.now());
    }

    /**
     * 폐기: 상태를 REVOKED로 바꾸고 폐기 시각을 기록 (줄은 지우지 않음 → 언제 어떤 키가 있었는지 기록이 남음)
     * 이미 폐기된 키면 아무것도 바꾸지 않는다 → 여러 번 폐기해도 결과가 같고(멱등), 처음 폐기한 시각이 유지된다
     *
     * @return 이번 호출로 실제로 폐기됐으면 true, 이미 폐기된 상태였으면 false (로그용)
     */
    public boolean revoke() {
        if (status == ApiKeyStatus.REVOKED) {
            return false;
        }
        this.status = ApiKeyStatus.REVOKED;
        this.revokedAt = LocalDateTime.now();
        return true;
    }
}
