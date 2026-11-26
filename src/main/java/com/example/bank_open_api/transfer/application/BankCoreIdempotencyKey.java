package com.example.bank_open_api.transfer.application;

// [무엇] bank_core에 보낼 Idempotency-Key 만들기
// [역할] 앱이 보낸 원래 키에 앱 번호를 붙여 "app-{appId}:{원래 키}" 로 바꾼다
// [누가 쓰나] TransferService
//
// ── 왜 바꿔서 보내나? (★ 핵심) ──
//   모든 앱이 bank_core의 "연동 회원" 한 명으로 이체한다. bank_core 입장에서는 모든 요청이 같은 사람에게서 온다.
//   그래서 A앱과 B앱이 우연히 같은 키 "order-1"을 쓰면, bank_core는 B앱의 이체를 A앱 이체의 재전송으로 보고
//   A앱의 결과를 돌려주거나(내용이 같을 때) 422로 거절한다(내용이 다를 때). → 앱 번호를 붙여 키 공간을 앱마다 나눈다
//     A앱(1): order-1 → app-1:order-1
//     B앱(2): order-1 → app-2:order-1      (서로 절대 같아질 수 없음)
//
// ── bank_core 길이 제한(64자)을 넘으면? ──
//   우리도 원래 키를 64자까지 허용하므로, "app-{appId}:"를 붙이면 64자를 넘을 수 있다 (예: 앱 3번 + 64자 키 = 70자).
//   그럴 때만 원래 키 대신 원래 키의 SHA-256 지문을 쓴다:
//     app-{appId}:~{SHA-256(원래 키)를 Base64URL로 바꾼 것, 64자에 맞게 앞부분}
//   - 같은 앱 + 같은 키 → 항상 같은 결과 (재요청이 같은 키로 전달됨 → bank_core 멱등성 그대로 동작)
//   - '~'는 원래 키에 쓸 수 없는 글자(허용: 영문·숫자·-·_)라서, 짧은 키를 그대로 붙인 것과 절대 겹치지 않는다
//   - 앞에 "app-{appId}:"는 그대로라 다른 앱과도 겹치지 않는다
//   - 지문 길이는 앱 번호가 가장 클 때(19자리)도 39글자(약 234비트) 이상 → 두 키가 같은 지문이 될 가능성은 사실상 0
//   짧은 키는 그대로 보여서 bank_core DB에서 사람이 읽고 찾기 쉽다.

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public final class BankCoreIdempotencyKey {

    // bank_core idempotency_key 열 길이 (VARCHAR(64), TransferService.IDEMPOTENCY_KEY_MAX_LENGTH)
    static final int BANK_CORE_MAX_LENGTH = 64;

    // 긴 키를 지문으로 바꿨다는 표시. 원래 키에는 쓸 수 없는 글자여야 한다
    static final char HASHED_MARK = '~';

    private BankCoreIdempotencyKey() {
    }

    /**
     * @param appId       요청한 앱 번호
     * @param originalKey 앱이 보낸 키 (컨트롤러에서 형식 검사 끝: 영문·숫자·-·_ 1~64자)
     * @return bank_core에 보낼 키 (항상 64자 이하)
     */
    public static String of(Long appId, String originalKey) {
        String prefix = "app-" + appId + ":";
        String direct = prefix + originalKey;
        if (direct.length() <= BANK_CORE_MAX_LENGTH) {
            return direct;
        }
        String hashedPrefix = prefix + HASHED_MARK;
        String fingerprint = sha256Base64Url(originalKey);   // 43글자
        int room = BANK_CORE_MAX_LENGTH - hashedPrefix.length();
        return hashedPrefix + fingerprint.substring(0, Math.min(room, fingerprint.length()));
    }

    private static String sha256Base64Url(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", e);   // 모든 Java에 들어 있어 실제로는 일어나지 않음
        }
    }
}
