package com.example.bank_open_api.apikey.domain;

// [무엇] API 키 상태
// [역할] 키가 지금 쓸 수 있는지(ACTIVE), 폐기됐는지(REVOKED) 구분
// [누가 쓰나] ApiKey 엔티티, ApiKeyService(활성 키 개수 검사), 응답(ApiKeyResponse)
//            나중에 API 키 인증 필터도 ACTIVE인 키만 통과시킨다

public enum ApiKeyStatus {
    ACTIVE,    // 사용 가능
    REVOKED    // 폐기됨. 다시 ACTIVE로 되돌리지 않는다 (필요하면 새로 발급)
}
