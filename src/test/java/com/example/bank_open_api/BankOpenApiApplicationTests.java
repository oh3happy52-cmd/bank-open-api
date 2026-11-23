package com.example.bank_open_api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BankOpenApiApplicationTests {

	@Test
	void contextLoads() {
	}

}
