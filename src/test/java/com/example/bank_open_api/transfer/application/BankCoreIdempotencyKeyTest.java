package com.example.bank_open_api.transfer.application;

// [무엇] bank_core용 Idempotency-Key 만들기 단위 테스트 (스프링·DB 없음)
// [역할] - 짧은 키: "app-{appId}:{원래 키}" 그대로
//        - 다른 앱의 같은 키 → 다른 결과 / 같은 앱의 같은 키 → 항상 같은 결과
//        - 붙이면 64자를 넘는 키: "app-{appId}:~지문" 으로 64자 이하, 앱 번호가 가장 커도(19자리) 64자 이하
//        - 긴 키끼리도 서로 다르고, 짧은 키를 그대로 붙인 결과와 겹치지 않는다
// [누가 쓰나] ./gradlew test (Docker 필요 없음)

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BankCoreIdempotencyKeyTest {

    private static final String KEY_64 = "a".repeat(64);

    @Test
    void 짧은_키는_앱_번호를_붙여_그대로_보낸다() {
        assertThat(BankCoreIdempotencyKey.of(3L, "order-1")).isEqualTo("app-3:order-1");
    }

    @Test
    void 다른_앱의_같은_키는_서로_다르고_같은_앱의_같은_키는_항상_같다() {
        assertThat(BankCoreIdempotencyKey.of(1L, "order-1")).isNotEqualTo(BankCoreIdempotencyKey.of(2L, "order-1"));
        // "app-1" + "1:x" 와 "app-11" + ":x" 처럼 경계가 헷갈릴 수 있는 경우도 ':'가 구분해 줌 (키에 ':'는 못 씀)
        assertThat(BankCoreIdempotencyKey.of(1L, "1-x")).isNotEqualTo(BankCoreIdempotencyKey.of(11L, "-x"));
        assertThat(BankCoreIdempotencyKey.of(7L, KEY_64)).isEqualTo(BankCoreIdempotencyKey.of(7L, KEY_64));
    }

    @Test
    void 붙여서_64자를_넘으면_지문으로_바꿔_64자_이하로_만든다() {
        String converted = BankCoreIdempotencyKey.of(3L, KEY_64);   // 그대로면 "app-3:" 6자 + 64자 = 70자

        assertThat(converted).hasSizeLessThanOrEqualTo(64);
        assertThat(converted).startsWith("app-3:~");
        assertThat(converted).doesNotContain(KEY_64);
    }

    @Test
    void 경계_길이_정확히_64자면_그대로_65자가_되면_지문() {
        String fits = "k".repeat(58);    // "app-3:" 6자 + 58 = 64자 → 그대로
        String over = "k".repeat(59);    // 65자 → 지문
        assertThat(BankCoreIdempotencyKey.of(3L, fits)).isEqualTo("app-3:" + fits).hasSize(64);
        assertThat(BankCoreIdempotencyKey.of(3L, over)).startsWith("app-3:~").hasSizeLessThanOrEqualTo(64);
    }

    @Test
    void 앱_번호가_가장_커도_64자를_넘지_않는다() {
        String converted = BankCoreIdempotencyKey.of(Long.MAX_VALUE, KEY_64);   // 앱 번호 19자리
        assertThat(converted).startsWith("app-" + Long.MAX_VALUE + ":~").hasSize(64);
    }

    @Test
    void 긴_키끼리도_서로_다르고_짧은_키_결과와_겹치지_않는다() {
        String a = BankCoreIdempotencyKey.of(3L, "a".repeat(64));
        String b = BankCoreIdempotencyKey.of(3L, "a".repeat(63) + "b");
        assertThat(a).isNotEqualTo(b);
        // 짧은 키에는 '~'가 들어갈 수 없으므로(컨트롤러가 막음) 지문 결과와 절대 같아질 수 없다
        assertThat(BankCoreIdempotencyKey.of(3L, "short")).doesNotContain("~");
    }
}
