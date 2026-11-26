package com.example.bank_open_api;

// [무엇] 테스트용 MySQL·Redis 컨테이너 설정
// [역할] 테스트를 실행하면 Docker로 MySQL 8.4와 Redis 7을 새로 띄우고, 스프링이 거기에 자동으로 연결하게 함 (@ServiceConnection)
//        → 테스트마다 깨끗한 진짜 DB·Redis에서 돌아가서, 개발용 컨테이너(docker-compose의 3308·6380)를 건드리지 않음
//        버전은 docker-compose.yml과 같은 mysql:8.4, redis:7-alpine으로 고정 (latest는 언제 바뀔지 몰라서)
// [누가 쓰나] 모든 통합 테스트가 @Import(TestcontainersConfiguration.class)로 가져다 씀
//            (Redis가 꺼진 상황을 테스트하는 RateLimitRedisDownTest만 MySQL만 따로 띄운다)

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection   // 컨테이너의 접속 주소·계정을 spring.datasource.* 대신 자동으로 사용
	MySQLContainer mysqlContainer() {
		return new MySQLContainer(DockerImageName.parse("mysql:8.4"));
	}

	// Redis 전용 컨테이너 클래스가 따로 없어서 범용 GenericContainer를 쓴다
	// name = "redis": "이 컨테이너는 Redis다"라고 알려 줘야 spring.data.redis.host/port 대신 이 컨테이너 주소를 쓴다
	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
	}

}
