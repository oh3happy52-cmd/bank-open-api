package com.example.bank_open_api.webhook.application;

// [무엇] WebhookEventRecorder 단위 테스트 (DB 없이, 저장소는 가짜)
// [역할] 알림 저장이 실패해도 예외를 밖으로 던지지 않고(= 이체 응답 201은 그대로) ERROR 로그만 남긴다
// [누가 쓰나] ./gradlew test (Docker 필요 없음)

import com.example.bank_open_api.transfer.application.TransferResult;
import com.example.bank_open_api.webhook.domain.WebhookDeliveryRepository;
import com.example.bank_open_api.webhook.domain.WebhookEndpoint;
import com.example.bank_open_api.webhook.domain.WebhookEndpointRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class WebhookEventRecorderTest {

    @Test
    void 저장이_실패해도_예외를_던지지_않고_ERROR_로그만_남긴다(CapturedOutput output) {
        WebhookEndpointRepository endpoints = mock(WebhookEndpointRepository.class);
        WebhookDeliveryRepository deliveries = mock(WebhookDeliveryRepository.class);
        when(endpoints.findByAppId(7L)).thenReturn(Optional.of(
                WebhookEndpoint.create(7L, "https://x.example/w", "v1:abc", "whsec_abcdef", LocalDateTime.now())));
        when(deliveries.existsByAppIdAndTransferIdAndEventType(anyLong(), anyLong(), anyString())).thenReturn(false);
        when(deliveries.saveAndFlush(any())).thenThrow(new DataAccessResourceFailureException("DB 연결 끊김"));

        WebhookEventRecorder recorder = new WebhookEventRecorder(endpoints, deliveries, JsonMapper.builder().build(),
                Clock.systemDefaultZone());

        assertThatCode(() -> recorder.recordTransferCompleted(7L,
                new TransferResult(99L, "200-0001", "200-0002", 1000, "COMPLETED")))
                .doesNotThrowAnyException();
        assertThat(output.getAll()).contains("ERROR").contains("웹훅 알림 저장 실패(이체 응답은 정상)")
                .contains("appId=7").contains("transferId=99")
                .doesNotContain("200-0001");
    }
}
