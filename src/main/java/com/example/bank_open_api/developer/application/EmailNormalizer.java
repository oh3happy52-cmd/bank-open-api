package com.example.bank_open_api.developer.application;

// [무엇] 이메일 정리·가리기 도우미
// [역할] ① normalize: 앞뒤 공백을 지우고 소문자로 바꿈 → "Foo@A.com"과 "foo@a.com"을 같은 사람으로 봄
//        ② mask: 로그에 남길 때 이메일 일부를 가림 → "abcdef@example.com" → "ab***@example.com"
// [누가 쓰나] DeveloperService(가입), AuthService(로그인, 로그인 실패 로그)

import java.util.Locale;

final class EmailNormalizer {

    private EmailNormalizer() {
    }

    // 저장·검색 전에 항상 같은 모양으로 맞춘다
    // Locale.ROOT: 컴퓨터 언어 설정(예: 터키어)에 따라 대소문자 변환 결과가 달라지는 것을 막음
    static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    // 로그용으로 앞 2글자만 남기고 가린다 (개인정보를 로그에 그대로 남기지 않기 위해)
    // 아이디 부분이 2글자 이하면 첫 글자만 남김. @가 없으면 전부 가림
    static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);   // "@example.com"
        int visible = local.length() > 2 ? 2 : 1;
        return local.substring(0, visible) + "***" + domain;
    }
}
