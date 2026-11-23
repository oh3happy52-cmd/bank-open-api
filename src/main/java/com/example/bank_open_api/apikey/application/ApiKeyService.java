package com.example.bank_open_api.apikey.application;

// [무엇] API 키 발급·목록·폐기 서비스
// [역할] ① 발급: 내 앱 확인 + 앱 줄 잠금 → ACTIVE 키가 이미 2개면 409 → 새 키 생성 → 해시만 저장 → 원문은 한 번만 돌려줌
//        ② 목록: 내 앱의 키 목록 (원문·해시 없이)
//        ③ 폐기: 내 앱의 키를 REVOKED로 (이미 폐기됐어도 성공 처리)
// [누가 쓰나] ApiKeyController (POST/GET /api/v1/apps/{appId}/keys, DELETE /api/v1/apps/{appId}/keys/{keyId})
//
// 404 판단 순서: 앱을 먼저 확인한다.
//   - 남의 앱·없는 앱 → 키 번호가 무엇이든 APP_NOT_FOUND (AppService와 같은 이유: 존재 여부를 알려 주지 않기 위해)
//   - 내 앱인데 그 앱에 없는 키(다른 앱의 키 포함) → API_KEY_NOT_FOUND
//   → 남의 앱에 대해서는 키가 있는지 없는지 어떤 정보도 나가지 않는다.
//
// 로그 규칙: 키 원문과 해시는 절대 남기지 않고 keyId, appId, keyPrefix만 남긴다.

import com.example.bank_open_api.apikey.application.ApiKeyGenerator.GeneratedApiKey;
import com.example.bank_open_api.apikey.domain.ApiKey;
import com.example.bank_open_api.apikey.domain.ApiKeyRepository;
import com.example.bank_open_api.apikey.domain.ApiKeyStatus;
import com.example.bank_open_api.app.application.AppService;
import com.example.bank_open_api.common.exception.BusinessException;
import com.example.bank_open_api.common.exception.ErrorCode;
import com.example.bank_open_api.common.security.LoginDeveloper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    // 앱당 ACTIVE 키 최대 개수. 2개인 이유: 키 교체(새 키 발급 → 서비스에 적용 → 옛 키 폐기) 동안 잠깐 둘 다 필요
    static final int MAX_ACTIVE_KEYS_PER_APP = 2;

    private final ApiKeyRepository apiKeyRepository;
    private final ApiKeyGenerator apiKeyGenerator;
    private final AppService appService;

    // 발급 결과: 저장된 키 정보 + 이번 한 번만 보여 줄 원문
    // toString()에서 원문을 가린다 → 실수로 로그에 찍어도 원문이 남지 않게
    public record IssuedApiKey(ApiKey apiKey, String rawKey) {
        @Override
        public String toString() {
            return "IssuedApiKey[keyId=" + apiKey.getId() + ", keyPrefix=" + apiKey.getKeyPrefix() + ", rawKey=***]";
        }
    }

    @Transactional
    public IssuedApiKey issue(LoginDeveloper loginDeveloper, Long appId) {
        // 1. 내 앱인지 확인하면서 앱 줄을 잠근다 (없거나 남의 앱이면 404)
        //    잠그는 이유: 활성 키가 1개일 때 발급 요청 두 개가 동시에 오면, 둘 다 "지금 1개"를 보고 통과해서
        //    활성 키가 3개가 될 수 있다. 앱 줄을 잠그면 같은 앱의 발급은 한 번에 하나씩 처리된다.
        //    (잠금은 이 메서드의 트랜잭션이 끝날 때 풀린다. 다른 앱의 발급은 기다리지 않는다)
        appService.lockMyApp(loginDeveloper, appId);

        // 2. ACTIVE 키가 이미 최대 개수면 409
        long activeCount = apiKeyRepository.countByAppIdAndStatus(appId, ApiKeyStatus.ACTIVE);
        if (activeCount >= MAX_ACTIVE_KEYS_PER_APP) {
            log.info("API 키 발급 거부: appId={}, activeCount={}", appId, activeCount);
            throw new BusinessException(ErrorCode.API_KEY_LIMIT_EXCEEDED);
        }

        // 3. 새 키 생성 → 해시와 앞 12글자만 저장 (원문은 DB에 가지 않음)
        GeneratedApiKey generated = apiKeyGenerator.generate();
        ApiKey apiKey = apiKeyRepository.save(ApiKey.issue(appId, generated.keyPrefix(), generated.keyHash()));

        log.info("API 키 발급: keyId={}, appId={}, keyPrefix={}", apiKey.getId(), appId, apiKey.getKeyPrefix());

        // 4. 원문은 이 응답에서 한 번만 돌려준다. 이후에는 서버도 원문을 알 수 없다 (해시만 있으므로)
        return new IssuedApiKey(apiKey, generated.rawKey());
    }

    @Transactional(readOnly = true)
    public List<ApiKey> findKeys(LoginDeveloper loginDeveloper, Long appId) {
        appService.findMyApp(loginDeveloper, appId);   // 없거나 남의 앱이면 404
        return apiKeyRepository.findAllByAppIdOrderByCreatedAtDescIdDesc(appId);
    }

    @Transactional
    public void revoke(LoginDeveloper loginDeveloper, Long appId, Long keyId) {
        appService.findMyApp(loginDeveloper, appId);   // 없거나 남의 앱이면 404 (키 번호는 보지도 않음)

        ApiKey apiKey = apiKeyRepository.findByIdAndAppId(keyId, appId)
                .orElseThrow(() -> new BusinessException(ErrorCode.API_KEY_NOT_FOUND));

        // 이미 폐기된 키면 아무것도 바뀌지 않는다 (처음 폐기 시각 유지). 응답은 어느 쪽이든 204
        // 바뀐 상태는 트랜잭션이 끝날 때 JPA가 알아서 UPDATE 한다 (변경 감지)
        boolean revokedNow = apiKey.revoke();
        if (revokedNow) {
            log.info("API 키 폐기: keyId={}, appId={}, keyPrefix={}", keyId, appId, apiKey.getKeyPrefix());
        } else {
            log.info("API 키 재폐기 요청(이미 폐기됨): keyId={}, appId={}, keyPrefix={}", keyId, appId, apiKey.getKeyPrefix());
        }
    }
}
