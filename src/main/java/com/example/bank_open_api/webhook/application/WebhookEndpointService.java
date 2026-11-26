package com.example.bank_open_api.webhook.application;

// [무엇] 웹훅 설정 서비스 (개발자 포털)
// [역할] ① 등록·수정(PUT): 처음이면 비밀키를 새로 만들어 암호화 저장하고 원문을 1회만 돌려줌. 이미 있으면 주소만 바꾸고 다시 켬
//        ② 조회(GET): 주소, 비밀키 앞부분, 켜짐 여부
//        ③ 끄기(DELETE): enabled=false. 새 알림을 만들지 않고, 남은 알림은 보낼 때 CANCELED
//        ④ 비밀키 교체(rotate): 새 비밀키를 만들어 원문을 1회만 돌려줌. 이후 보내는 알림(재전송 포함)은 새 키로 서명
//        ⑤ 전송 기록 목록: 최신순 페이지
//        모든 기능은 "내 앱"만. 없거나 남의 앱이면 404 APP_NOT_FOUND (1주차와 같은 원칙)
// [누가 쓰나] WebhookController
//
// 등록·교체는 앱 줄을 잠그고(AppService.lockMyApp) 처리한다 → 같은 앱에 동시에 두 번 등록해도 설정이 2개가 되지 않음

import com.example.bank_open_api.app.application.AppService;
import com.example.bank_open_api.common.exception.BusinessException;
import com.example.bank_open_api.common.exception.ErrorCode;
import com.example.bank_open_api.common.security.LoginDeveloper;
import com.example.bank_open_api.webhook.crypto.WebhookSecretCipher;
import com.example.bank_open_api.webhook.crypto.WebhookSecretGenerator;
import com.example.bank_open_api.webhook.domain.WebhookDelivery;
import com.example.bank_open_api.webhook.domain.WebhookDeliveryRepository;
import com.example.bank_open_api.webhook.domain.WebhookEndpoint;
import com.example.bank_open_api.webhook.domain.WebhookEndpointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookEndpointService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private final AppService appService;
    private final WebhookEndpointRepository endpointRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookUrlValidator urlValidator;
    private final WebhookSecretGenerator secretGenerator;
    private final WebhookSecretCipher secretCipher;
    private final Clock clock;

    /**
     * 등록·수정 결과
     *
     * @param secret 처음 등록일 때만 비밀키 원문, 수정이면 null
     */
    public record UpsertResult(WebhookEndpoint endpoint, String secret, boolean created) {
        @Override
        public String toString() {
            return "UpsertResult[endpointId=" + endpoint.getId() + ", secret=****, created=" + created + "]";
        }
    }

    public record RotateResult(String secret, String secretPrefix) {
        @Override
        public String toString() {
            return "RotateResult[secret=****, secretPrefix=" + secretPrefix + "]";
        }
    }

    @Transactional
    public UpsertResult upsert(LoginDeveloper loginDeveloper, Long appId, String url) {
        appService.lockMyApp(loginDeveloper, appId);   // 404 확인 + 같은 앱 동시 등록 막기 (트랜잭션 끝까지 잠금)
        String validUrl = urlValidator.validate(url);
        LocalDateTime now = LocalDateTime.now(clock);

        var existing = endpointRepository.findByAppId(appId);
        if (existing.isPresent()) {
            WebhookEndpoint endpoint = existing.get();
            endpoint.updateUrl(validUrl, now);
            log.info("웹훅 주소 수정: appId={}, secretPrefix={}", appId, endpoint.getSecretPrefix());
            return new UpsertResult(endpoint, null, false);
        }

        var generated = secretGenerator.generate();
        WebhookEndpoint endpoint = endpointRepository.save(WebhookEndpoint.create(
                appId, validUrl, secretCipher.encrypt(generated.secret(), appId), generated.displayPrefix(), now));
        log.info("웹훅 등록: appId={}, secretPrefix={}", appId, generated.displayPrefix());
        return new UpsertResult(endpoint, generated.secret(), true);
    }

    @Transactional(readOnly = true)
    public WebhookEndpoint get(LoginDeveloper loginDeveloper, Long appId) {
        appService.findMyApp(loginDeveloper, appId);
        return findEndpoint(appId);
    }

    @Transactional
    public void disable(LoginDeveloper loginDeveloper, Long appId) {
        appService.findMyApp(loginDeveloper, appId);
        findEndpoint(appId).disable(LocalDateTime.now(clock));
        log.info("웹훅 끄기: appId={}", appId);
    }

    @Transactional
    public RotateResult rotateSecret(LoginDeveloper loginDeveloper, Long appId) {
        appService.lockMyApp(loginDeveloper, appId);
        WebhookEndpoint endpoint = findEndpoint(appId);
        var generated = secretGenerator.generate();
        endpoint.rotateSecret(secretCipher.encrypt(generated.secret(), appId), generated.displayPrefix(),
                LocalDateTime.now(clock));
        log.info("웹훅 비밀키 교체: appId={}, secretPrefix={}", appId, generated.displayPrefix());
        return new RotateResult(generated.secret(), generated.displayPrefix());
    }

    /**
     * @param page 0부터
     * @param size 1~100 (100을 넘으면 100으로)
     */
    @Transactional(readOnly = true)
    public Page<WebhookDelivery> deliveries(LoginDeveloper loginDeveloper, Long appId, int page, int size) {
        appService.findMyApp(loginDeveloper, appId);
        if (page < 0 || size < 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "page는 0 이상, size는 1 이상이어야 합니다");
        }
        int pageSize = Math.min(size, MAX_PAGE_SIZE);
        // 최신순. 같은 시각이면 나중에 저장된 것(id 큰 것)이 먼저
        Sort latestFirst = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        return deliveryRepository.findAllByAppId(appId, PageRequest.of(page, pageSize, latestFirst));
    }

    // 내 앱인 것은 확인이 끝난 상태. 웹훅을 아직 등록하지 않았으면 404 WEBHOOK_NOT_FOUND
    private WebhookEndpoint findEndpoint(Long appId) {
        return endpointRepository.findByAppId(appId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WEBHOOK_NOT_FOUND));
    }
}
