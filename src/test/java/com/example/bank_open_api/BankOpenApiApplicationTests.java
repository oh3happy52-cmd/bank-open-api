package com.example.bank_open_api;

// [무엇] 서버 기본 시작 테스트
// [역할] 스프링 설정 전체(보안, JPA, Flyway 마이그레이션, ddl-auto=validate 검사)가 오류 없이 켜지는지 확인
// [누가 쓰나] ./gradlew test 실행 시 JUnit이 실행

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

// jwt.secret: .env가 없는 곳(CI 등)에서도 서버가 켜지도록 테스트 전용 키를 넣음 (진짜 키 아님)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "jwt.secret=test-only-jwt-secret-key-0123456789abcdef")
class BankOpenApiApplicationTests {

	@Test
	void contextLoads() {
	}

}
