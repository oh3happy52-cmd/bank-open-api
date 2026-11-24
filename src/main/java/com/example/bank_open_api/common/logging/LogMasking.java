package com.example.bank_open_api.common.logging;

// [무엇] 로그용 가리기(마스킹) 도구
// [역할] 로그에 계좌번호 전체가 남지 않도록 끝 4자리만 남긴다
//        - last4("123-4567890")                      → "7890"
//        - maskPath("/open/v1/accounts/123-4567890") → "/open/v1/accounts/****7890"
// [누가 쓰나] BankCoreClient(계좌 조회 로그), ApiKeyAuthenticationFilter(인증 실패 로그의 uri)

public final class LogMasking {

    // 계좌번호가 들어 있는 주소의 앞부분 (/open/v1/accounts/{accountNumber})
    private static final String ACCOUNTS_PATH = "/open/v1/accounts/";

    private LogMasking() {
    }

    // 계좌번호 끝 4자리. 4자리 이하면 전체가 드러나므로 ****로 가린다
    public static String last4(String accountNumber) {
        if (accountNumber == null || accountNumber.length() <= 4) {
            return "****";
        }
        return accountNumber.substring(accountNumber.length() - 4);
    }

    // 주소 안의 계좌번호 자리를 "****끝4자리"로 바꾼다. 계좌번호가 없는 주소는 그대로 돌려준다
    // 예) /open/v1/accounts/123-4567890        → /open/v1/accounts/****7890
    //     /open/v1/accounts/123-4567890/extra  → /open/v1/accounts/****7890/extra
    //     /open/v1/ping                        → /open/v1/ping
    public static String maskPath(String path) {
        if (path == null || !path.startsWith(ACCOUNTS_PATH)) {
            return path;
        }
        String rest = path.substring(ACCOUNTS_PATH.length());
        int slash = rest.indexOf('/');
        String accountNumber = slash < 0 ? rest : rest.substring(0, slash);
        String tail = slash < 0 ? "" : rest.substring(slash);
        if (accountNumber.isEmpty()) {
            return path;
        }
        return ACCOUNTS_PATH + "****" + last4(accountNumber) + tail;
    }
}
