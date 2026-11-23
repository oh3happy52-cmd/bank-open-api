package com.example.bank_open_api.developer.domain;

// [무엇] 개발자 엔티티 (DB의 developers 테이블 한 줄)
// [역할] 개발자 포털 회원의 이메일, 비밀번호 해시, 이름, 가입 시각을 들고 있음
// [누가 쓰나] DeveloperService(회원가입·내 정보), AuthService(로그인), DeveloperRepository
//
// 테이블은 Flyway(V1__create_developers.sql)가 만들고, JPA는 ddl-auto=validate로
// 이 클래스와 테이블 구조가 맞는지만 검사한다.

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
@Table(name = "developers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)   // JPA가 DB에서 읽어 올 때 쓰는 빈 생성자 (밖에서는 못 씀)
public class Developer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)   // MySQL AUTO_INCREMENT
    private Long id;

    // 로그인 아이디. 항상 소문자로 저장 (DeveloperService가 바꿔서 넘김)
    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    // BCrypt 해시. 원래 비밀번호는 어디에도 저장하지 않음
    // 응답(DeveloperResponse)에는 절대 넣지 않는다
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private Developer(String email, String passwordHash, String name, LocalDateTime createdAt) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.name = name;
        this.createdAt = createdAt;
    }

    // 새 개발자 만들기 (회원가입)
    // passwordHash: 원래 비밀번호가 아니라 BCrypt로 바꾼 해시를 받는다
    public static Developer create(String email, String passwordHash, String name) {
        return new Developer(email, passwordHash, name, LocalDateTime.now());
    }
}
