package com.example.bank_open_api.webhook.domain;

// [무엇] 웹훅 설정 엔티티 (DB의 webhook_endpoints 테이블 한 줄)
// [역할] 앱의 알림 받을 주소, 비밀키 암호문, 화면 표시용 앞부분, 켜짐 여부를 들고 있다
//        비밀키 원문 칸은 없다 → 실수로라도 평문이 DB에 저장될 수 없다
// [누가 쓰나] WebhookEndpointService, WebhookEventRecorder, WebhookDispatcher

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
@Table(name = "webhook_endpoints")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_id", nullable = false, unique = true)
    private Long appId;

    @Column(name = "url", nullable = false, length = 2048)
    private String url;

    // "v1:" + Base64(IV + 암호문 + 태그). 원문은 WebhookSecretCipher로만 꺼낼 수 있다
    @Column(name = "secret_ciphertext", nullable = false)
    private String secretCiphertext;

    @Column(name = "secret_prefix", nullable = false, length = 12)
    private String secretPrefix;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "secret_rotated_at", nullable = false)
    private LocalDateTime secretRotatedAt;

    // 처음 등록 (켜진 상태로 시작)
    public static WebhookEndpoint create(Long appId, String url, String secretCiphertext, String secretPrefix,
                                         LocalDateTime now) {
        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.appId = appId;
        endpoint.url = url;
        endpoint.secretCiphertext = secretCiphertext;
        endpoint.secretPrefix = secretPrefix;
        endpoint.enabled = true;
        endpoint.createdAt = now;
        endpoint.updatedAt = now;
        endpoint.secretRotatedAt = now;
        return endpoint;
    }

    // 주소 바꾸기 + 다시 켜기 (PUT은 "이 주소로 받겠다"는 뜻이라 꺼져 있었으면 켠다. 비밀키는 그대로)
    public void updateUrl(String url, LocalDateTime now) {
        this.url = url;
        this.enabled = true;
        this.updatedAt = now;
    }

    public void disable(LocalDateTime now) {
        if (enabled) {
            this.enabled = false;
            this.updatedAt = now;
        }
    }

    public void rotateSecret(String secretCiphertext, String secretPrefix, LocalDateTime now) {
        this.secretCiphertext = secretCiphertext;
        this.secretPrefix = secretPrefix;
        this.secretRotatedAt = now;
    }
}
