package com.example.bank_open_api.transfer.application;

// [무엇] 이체 성공(201) 처리 담당 — 이체가 성공하면 반드시 여기를 한 번 지난다
// [역할] ① 성공 로그를 남긴다
//        ② 웹훅 알림을 아웃박스에 저장한다 (WebhookEventRecorder — 앱의 웹훅이 켜져 있을 때만, 보내기는 발송 일꾼이 따로)
//        성공 처리가 여기 한 곳에만 모여 있어서, 컨트롤러나 BankCoreClient를 고치지 않고 웹훅을 붙였다
// [누가 쓰나] TransferService (bank_core가 201을 준 뒤에만 부름. 실패한 이체는 오지 않는다)
//
// 같은 Idempotency-Key 재요청도 bank_core가 201(처음 결과)을 주므로 여기를 또 지난다.
// → WebhookEventRecorder가 UNIQUE(app_id, transfer_id, event_type)로 알림을 1줄만 남긴다 (두 번 보내지 않음)
// 알림 저장이 실패해도 recorder가 예외를 삼키므로 이체 응답(201)은 그대로 나간다
//
// ── 로그 규칙 ── 계좌번호는 끝 4자리만. 원래 Idempotency-Key는 비밀값이 아니라 남긴다

import com.example.bank_open_api.common.logging.LogMasking;
import com.example.bank_open_api.common.security.ApiClient;
import com.example.bank_open_api.webhook.application.WebhookEventRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TransferSuccessHandler {

    private final WebhookEventRecorder webhookEventRecorder;

    public void handle(ApiClient client, String idempotencyKey, TransferResult result) {
        log.info("이체 성공: appId={}, keyId={}, idempotencyKey={}, transferId={}, fromLast4={}, toLast4={}, amount={}, status={}",
                client.appId(), client.keyId(), idempotencyKey, result.transferId(),
                LogMasking.last4(result.fromAccountNumber()), LogMasking.last4(result.toAccountNumber()),
                result.amount(), result.status());

        webhookEventRecorder.recordTransferCompleted(client.appId(), result);
    }
}
