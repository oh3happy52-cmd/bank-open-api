package com.example.bank_open_api.app.domain;

// [무엇] 앱 저장소 (apps 테이블 읽기·쓰기)
// [역할] Spring Data JPA가 메서드 이름을 보고 SQL을 자동으로 만들어 줌
//        모든 조회 조건에 developerId가 들어가서, "내 앱"만 읽을 수 있게 되어 있다
// [누가 쓰나] AppService

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppRepository extends JpaRepository<App, Long> {

    // 같은 개발자에게 같은 이름의 앱이 이미 있는지 (등록 시 409 판단)
    boolean existsByDeveloperIdAndName(Long developerId, String name);

    // 내 앱 목록, 최근 등록순
    // 등록 시각이 같으면(아주 빠르게 연속 등록) id가 큰 쪽(나중에 저장된 쪽)이 먼저
    // SELECT * FROM apps WHERE developer_id = ? ORDER BY created_at DESC, id DESC
    List<App> findAllByDeveloperIdOrderByCreatedAtDescIdDesc(Long developerId);

    // 앱 상세: 번호와 주인이 둘 다 맞아야 찾아짐
    // → "없는 앱"과 "남의 앱"이 똑같이 결과 없음(Optional.empty)이 된다
    // SELECT * FROM apps WHERE id = ? AND developer_id = ?
    Optional<App> findByIdAndDeveloperId(Long id, Long developerId);
}
