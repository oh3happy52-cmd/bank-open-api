package com.example.bank_open_api.transfer.application;

// [무엇] 이체 전달 서비스
// [역할] ① 앱이 보낸 Idempotency-Key를 bank_core용 키("app-{appId}:{원래 키}")로 바꾼다 (BankCoreIdempotencyKey)
//        ② BankCoreClient로 bank_core에 이체를 전달한다 (401이면 재로그인 후 같은 키로 1회 재시도, 오류 변환도 거기서)
//        ③ 성공(201)이면 우리 결과(TransferResult)로 바꾸고, TransferSuccessHandler에 넘긴다 (성공 처리는 거기 한 곳)
//        실패는 BankCoreClient가 BusinessException으로 던지고, GlobalExceptionHandler가 {code, message}로 응답한다
// [누가 쓰나] TransferController (POST /open/v1/transfers)
//
// DB 트랜잭션이 없다: 우리 DB에는 아무것도 쓰지 않고, 돈은 bank_core가 옮긴다
// (사용량 기록은 필터가 따로 남긴다)

import com.example.bank_open_api.bankcore.BankCoreClient;
import com.example.bank_open_api.bankcore.BankCoreTransfer;
import com.example.bank_open_api.common.security.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TransferService {

    private final BankCoreClient bankCoreClient;
    private final TransferSuccessHandler successHandler;

    /**
     * @param client         API 키 인증을 통과한 호출자 (appId, keyId)
     * @param idempotencyKey 앱이 보낸 원래 키 (형식 검사 끝)
     */
    public TransferResult transfer(ApiClient client, String idempotencyKey,
                                   String fromAccountNumber, String toAccountNumber, long amount) {
        String bankCoreKey = BankCoreIdempotencyKey.of(client.appId(), idempotencyKey);

        BankCoreTransfer transferred = bankCoreClient.transfer(
                client.appId(), idempotencyKey, bankCoreKey, fromAccountNumber, toAccountNumber, amount);

        TransferResult result = new TransferResult(
                transferred.transferId(), fromAccountNumber, toAccountNumber,
                transferred.amount(), transferred.status());

        // ★ 이체 성공은 모두 여기를 지난다 (웹훅은 TransferSuccessHandler에 붙인다)
        successHandler.handle(client, idempotencyKey, result);
        return result;
    }
}
