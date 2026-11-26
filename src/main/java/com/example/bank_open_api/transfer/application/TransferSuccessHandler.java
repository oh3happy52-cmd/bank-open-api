package com.example.bank_open_api.transfer.application;

// [무엇] 이체 성공(201) 처리 담당 — 이체가 성공하면 반드시 여기를 한 번 지난다
// [역할] 지금은 성공 로그만 남긴다.
//        ★ 다음 단계(웹훅)는 이 handle() 안에 붙인다. 성공 처리가 여기 한 곳에만 모여 있으므로
//          컨트롤러나 BankCoreClient를 고치지 않고 웹훅 발송을 추가할 수 있다.
// [누가 쓰나] TransferService (bank_core가 201을 준 뒤에만 부름. 실패한 이체는 오지 않는다)
//
// 주의(웹훅을 붙일 때): 같은 Idempotency-Key 재요청도 bank_core가 201(처음 결과)을 주므로 여기를 또 지난다.
//                      웹훅을 두 번 보내지 않으려면 transferId로 "이미 보낸 이체인지" 확인해야 한다.
//
// ── 로그 규칙 ── 계좌번호는 끝 4자리만. 원래 Idempotency-Key는 비밀값이 아니라 남긴다

import com.example.bank_open_api.common.logging.LogMasking;
import com.example.bank_open_api.common.security.ApiClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class TransferSuccessHandler {

    public void handle(ApiClient client, String idempotencyKey, TransferResult result) {
        log.info("이체 성공: appId={}, keyId={}, idempotencyKey={}, transferId={}, fromLast4={}, toLast4={}, amount={}, status={}",
                client.appId(), client.keyId(), idempotencyKey, result.transferId(),
                LogMasking.last4(result.fromAccountNumber()), LogMasking.last4(result.toAccountNumber()),
                result.amount(), result.status());
    }
}
