package com.example.bank_open_api.app.api;

// [무엇] 앱 등록 요청 모양 (JSON 본문)
// [역할] 들어온 값이 규칙에 맞는지 @Valid로 검사. 하나라도 어기면 400 INVALID_REQUEST
// [누가 쓰나] AppController (POST /api/v1/apps)
//
// 앱 주인(developerId) 칸은 일부러 없다.
// 본문에 "developerId": 999 를 넣어 보내도 이 모양에 칸이 없으니 그냥 무시된다
// → 주인은 토큰의 subject(LoginDeveloper)에서만 정해지고, 남의 이름으로 앱을 만들 수 없다.

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAppRequest(
        // 필수, 1~50자 (공백만 있는 값은 @NotBlank가 막음)
        @NotBlank(message = "앱 이름을 입력하세요")
        @Size(max = 50, message = "앱 이름은 50자 이하여야 합니다")
        String name,

        // 선택, 200자 이하 (없어도 됨)
        @Size(max = 200, message = "앱 설명은 200자 이하여야 합니다")
        String description
) {
}
