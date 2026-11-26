package com.example.bank_open_api.webhook.domain;

// [무엇] 웹훅 설정 저장소 (webhook_endpoints 테이블)
// [역할] 앱 번호로 설정 1개를 찾는다 (UNIQUE(app_id))
// [누가 쓰나] WebhookEndpointService, WebhookEventRecorder, WebhookDispatcher

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, Long> {

    Optional<WebhookEndpoint> findByAppId(Long appId);
}
