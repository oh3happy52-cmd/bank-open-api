package com.example.bank_open_api.app.domain;

// [무엇] 앱 엔티티 (DB의 apps 테이블 한 줄)
// [역할] 개발자가 등록한 앱의 주인(개발자 번호), 이름, 설명, 등록 시각을 들고 있음
//        나중에 API 키는 이 앱 단위로 발급한다
// [누가 쓰나] AppService(등록·목록·상세), AppRepository
//
// 주인은 Developer 객체(@ManyToOne)가 아니라 개발자 번호(developerId) 숫자로만 들고 있다.
//   - 앱을 조회할 때 개발자 정보를 같이 불러올 필요가 없어 단순하다
//   - 없는 개발자 번호가 들어가는 것은 DB 외래 키(fk_apps_developer)가 막는다
// 테이블은 Flyway(V2__create_apps.sql)가 만들고, JPA는 ddl-auto=validate로 구조만 검사한다.

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "apps")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)   // JPA가 DB에서 읽어 올 때 쓰는 빈 생성자 (밖에서는 못 씀)
public class App {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)   // MySQL AUTO_INCREMENT
    private Long id;

    // 앱 주인 (developers.id). 요청 본문이 아니라 토큰의 subject(LoginDeveloper)에서만 정해진다
    @Column(name = "developer_id", nullable = false)
    private Long developerId;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    // 선택 항목. 비어 있으면 null
    @Column(name = "description", length = 200)
    private String description;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private App(Long developerId, String name, String description, LocalDateTime createdAt) {
        this.developerId = developerId;
        this.name = name;
        this.description = description;
        this.createdAt = createdAt;
    }

    // 새 앱 만들기 (등록)
    public static App create(Long developerId, String name, String description) {
        return new App(developerId, name, description, LocalDateTime.now());
    }
}
