package com.example.bank_open_api.apikey.application;

// [무엇] API 키 생성기
// [역할] 추측할 수 없는 새 키 원문을 만들고, DB에 저장할 해시와 화면 표시용 앞 12글자를 같이 돌려준다
//        키 모양: "boa_live_" + SecureRandom 32바이트를 Base64URL(패딩 없음)로 바꾼 43글자 = 총 52글자
// [누가 쓰나] ApiKeyService.issue (발급할 때 한 번)

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

@Component
public class ApiKeyGenerator {

    // 키 앞에 붙는 표시. "bank open api"의 live(실제 사용) 키라는 뜻
    // → 로그·코드에 실수로 키가 들어갔을 때 눈에 잘 띄고, 비밀 검사 도구가 찾기 쉬움
    static final String PREFIX = "boa_live_";

    // 무작위 부분의 크기: 32바이트 = 256비트 (추측 불가능한 크기)
    private static final int RANDOM_BYTES = 32;

    // 화면 표시용으로 저장하는 앞부분 길이: "boa_live_"(9글자) + 무작위 3글자
    static final int DISPLAY_PREFIX_LENGTH = 12;

    // SecureRandom: 암호용 난수 생성기 (일반 Random은 다음 값을 예측할 수 있어서 키에 쓰면 안 됨)
    // 여러 스레드가 함께 써도 안전해서 하나만 만들어 재사용한다
    private final SecureRandom secureRandom = new SecureRandom();

    // Base64URL: 주소·헤더에 넣어도 안전한 글자(A-Z a-z 0-9 - _)만 쓰는 방식
    // withoutPadding(): 끝에 붙는 "=" 를 빼서 키를 깔끔하게 (32바이트 → 정확히 43글자)
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    /**
     * 생성 결과
     * rawKey는 발급 응답에 한 번 담아 보내고 버린다. DB에는 keyPrefix와 keyHash만 저장한다.
     * toString()에서 rawKey를 가린다 → 실수로 로그에 이 객체를 찍어도 원문이 남지 않게.
     */
    public record GeneratedApiKey(String rawKey, String keyPrefix, String keyHash) {
        @Override
        public String toString() {
            return "GeneratedApiKey[keyPrefix=" + keyPrefix + ", rawKey=***, keyHash=***]";
        }
    }

    public GeneratedApiKey generate() {
        byte[] randomBytes = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(randomBytes);

        String rawKey = PREFIX + ENCODER.encodeToString(randomBytes);
        String keyPrefix = rawKey.substring(0, DISPLAY_PREFIX_LENGTH);
        String keyHash = ApiKeyHasher.sha256Hex(rawKey);
        return new GeneratedApiKey(rawKey, keyPrefix, keyHash);
    }
}
