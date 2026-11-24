package com.example.bank_open_api.common.security;

// [무엇] API 키로 인증된 호출자 정보 (어느 앱의 어느 키로 들어왔는가)
// [역할] ApiKeyAuthenticationFilter가 키 검사에 성공하면 이 값을 SecurityContext의 principal(= "누구인가")로 넣는다
//        키 원문·해시는 일부러 칸을 두지 않는다 → 컨트롤러나 로그로 실수로 새어 나갈 수 없다
// [누가 쓰나] ApiKeyAuthenticationFilter(만듦), /open/v1/** 컨트롤러(@AuthenticationPrincipal ApiClient로 꺼냄)
//            JWT 쪽의 LoginDeveloper와 짝을 이루는 "API 키 쪽" 로그인 정보

public record ApiClient(
        Long appId,       // 키가 속한 앱 번호 (apps.id) → 사용량 집계·호출 제한의 기준이 된다
        Long keyId,       // 키 번호 (api_keys.id)
        String keyPrefix  // 화면 표시용 앞 12글자 (예: boa_live_Xy3). 혼자서는 쓸 수 없어서 로그·응답에 써도 됨
) {
}
