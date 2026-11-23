package com.example.bank_open_api.app.application;

// [무엇] 앱 등록·목록·상세 서비스
// [역할] ① 등록: 이름 정리(앞뒤 공백 제거) → 같은 개발자 안에서 이름 중복이면 409 → 저장
//        ② 목록: 로그인한 개발자의 앱만 최근 등록순으로
//        ③ 상세: 로그인한 개발자의 앱만. 없거나 남의 앱이면 404
// [누가 쓰나] AppController (POST /api/v1/apps, GET /api/v1/apps, GET /api/v1/apps/{appId})
//
// 앱 주인은 항상 LoginDeveloper(토큰의 subject)에서만 받는다. 요청 본문에서 받지 않는다.

import com.example.bank_open_api.app.domain.App;
import com.example.bank_open_api.app.domain.AppRepository;
import com.example.bank_open_api.common.exception.BusinessException;
import com.example.bank_open_api.common.exception.ErrorCode;
import com.example.bank_open_api.common.security.LoginDeveloper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AppService {

    private final AppRepository appRepository;

    @Transactional
    public App create(LoginDeveloper loginDeveloper, String name, String description) {
        String trimmedName = name.trim();
        String normalizedDescription = normalizeDescription(description);

        // 같은 개발자 안에서 같은 이름이면 409 (다른 개발자는 같은 이름 가능)
        // MySQL 기본 비교 규칙이 대소문자를 구분하지 않아서 "MyApp"과 "myapp"도 같은 이름으로 본다
        // (동시에 두 요청이 들어와 여기를 둘 다 통과해도, DB UNIQUE 제약이 하나를 막고
        //  GlobalExceptionHandler가 같은 409로 바꿔 준다)
        if (appRepository.existsByDeveloperIdAndName(loginDeveloper.id(), trimmedName)) {
            throw new BusinessException(ErrorCode.DUPLICATE_APP_NAME);
        }

        App app = appRepository.save(App.create(loginDeveloper.id(), trimmedName, normalizedDescription));
        log.info("앱 등록: appId={}, developerId={}", app.getId(), loginDeveloper.id());
        return app;
    }

    @Transactional(readOnly = true)
    public List<App> findMyApps(LoginDeveloper loginDeveloper) {
        return appRepository.findAllByDeveloperIdOrderByCreatedAtDescIdDesc(loginDeveloper.id());
    }

    /**
     * 내 앱 상세
     *
     * 없는 앱과 남의 앱을 "둘 다 똑같이 404"로 응답하는 이유:
     * - 남의 앱에 403(권한 없음)을 주면 "그 번호의 앱은 존재한다"는 힌트가 된다.
     *   그러면 appId를 1, 2, 3… 바꿔 가며 요청해서 다른 회사의 앱이 몇 개인지, 어떤 번호가 쓰이는지 알아낼 수 있다.
     * - 그래서 번호와 주인을 한 번의 조회(findByIdAndDeveloperId)로 같이 확인한다.
     *   없는 앱이든 남의 앱이든 결과가 똑같이 "없음"이 되고, 같은 404(APP_NOT_FOUND)가 나간다.
     * - 응답 메시지에도 appId를 넣지 않아 두 경우의 응답 본문이 글자 하나까지 같다.
     */
    @Transactional(readOnly = true)
    public App findMyApp(LoginDeveloper loginDeveloper, Long appId) {
        return appRepository.findByIdAndDeveloperId(appId, loginDeveloper.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.APP_NOT_FOUND));
    }

    // 설명은 선택 항목: 없거나 공백뿐이면 null로 저장, 있으면 앞뒤 공백 제거
    private static String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        return description.trim();
    }
}
