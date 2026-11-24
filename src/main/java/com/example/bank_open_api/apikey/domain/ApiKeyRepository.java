package com.example.bank_open_api.apikey.domain;

// [무엇] API 키 저장소 (api_keys 테이블 읽기·쓰기)
// [역할] Spring Data JPA가 메서드 이름을 보고 SQL을 자동으로 만들어 줌
//        모든 조회 조건에 appId가 들어가서, 확인을 마친 "내 앱"의 키만 읽을 수 있게 되어 있다
// [누가 쓰나] ApiKeyService, ApiKeyAuthenticationFilter(findByKeyHash)

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    // 이 앱의 특정 상태 키 개수 (ACTIVE 최대 2개 검사)
    // SELECT COUNT(*) FROM api_keys WHERE app_id = ? AND status = ?
    long countByAppIdAndStatus(Long appId, ApiKeyStatus status);

    // 이 앱의 키 목록, 최근 발급순 (발급 시각이 같으면 id가 큰 쪽이 먼저)
    List<ApiKey> findAllByAppIdOrderByCreatedAtDescIdDesc(Long appId);

    // 폐기 대상: 키 번호와 앱 번호가 둘 다 맞아야 찾아짐 (다른 앱의 키 번호로는 찾을 수 없음)
    // SELECT * FROM api_keys WHERE id = ? AND app_id = ?
    Optional<ApiKey> findByIdAndAppId(Long id, Long appId);

    // API 키 인증(ApiKeyAuthenticationFilter): 요청 헤더의 키를 SHA-256 한 값으로 찾는다
    // SELECT * FROM api_keys WHERE key_hash = ?   (UNIQUE 인덱스 uk_api_keys_key_hash로 한 번에 찾음)
    // 상태(ACTIVE/REVOKED)는 조건에 넣지 않고 꺼낸 뒤 검사한다 → 로그에 "폐기된 키"라는 원인을 남길 수 있게
    Optional<ApiKey> findByKeyHash(String keyHash);
}
