package com.example.bank_open_api.developer.domain;

// [무엇] 개발자 저장소 (developers 테이블 읽기·쓰기)
// [역할] Spring Data JPA가 메서드 이름을 보고 SQL을 자동으로 만들어 줌
// [누가 쓰나] DeveloperService(가입 시 중복 확인·저장, 내 정보 조회), AuthService(로그인 시 이메일로 찾기)

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeveloperRepository extends JpaRepository<Developer, Long> {

    // SELECT * FROM developers WHERE email = ?
    Optional<Developer> findByEmail(String email);

    // 같은 이메일이 이미 있는지 (SELECT 1 ... LIMIT 1)
    boolean existsByEmail(String email);
}
