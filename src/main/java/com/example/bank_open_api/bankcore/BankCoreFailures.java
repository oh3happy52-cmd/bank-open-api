package com.example.bank_open_api.bankcore;

// [무엇] bank_core 호출 중 "응답 자체를 못 받은" 실패를 우리 오류로 바꾸는 도구
// [역할] RestClient가 던지는 ResourceAccessException(네트워크 문제)을 두 가지로 나눈다
//        - 제한 시간 초과(SocketTimeoutException 등) → 504 BANK_CORE_TIMEOUT
//        - 그 밖(연결 거부 = bank_core가 꺼져 있음, 주소 못 찾음 등) → 502 BANK_CORE_UNAVAILABLE
// [누가 쓰나] BankCoreTokenProvider(로그인), BankCoreClient(계좌 조회)
//
// 주의: ResourceAccessException의 메시지에는 요청 주소 전체(계좌번호 포함)가 들어 있어서 로그에 그대로 찍지 않는다.

import com.example.bank_open_api.common.exception.BusinessException;
import com.example.bank_open_api.common.exception.ErrorCode;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;

final class BankCoreFailures {

    private BankCoreFailures() {
    }

    // 로그에 찍을 실패 이름 (상태 코드 자리에 대신 넣음)
    static final String TIMEOUT = "TIMEOUT";
    static final String CONNECTION_FAILED = "CONNECTION_FAILED";

    // 원인 사슬(cause → cause → ...)을 따라가며 제한 시간 초과인지 확인
    static boolean isTimeout(ResourceAccessException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    // 로그용 이름: TIMEOUT 또는 CONNECTION_FAILED
    static String label(ResourceAccessException e) {
        return isTimeout(e) ? TIMEOUT : CONNECTION_FAILED;
    }

    // 우리 쪽 오류로 바꾸기 (메시지는 ErrorCode의 고정 문구만 → 계좌번호·주소가 응답·로그에 섞이지 않음)
    static BusinessException toBusinessException(ResourceAccessException e) {
        return new BusinessException(isTimeout(e) ? ErrorCode.BANK_CORE_TIMEOUT : ErrorCode.BANK_CORE_UNAVAILABLE);
    }

    // 시작 시각(System.nanoTime)부터 지금까지 걸린 밀리초
    static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
