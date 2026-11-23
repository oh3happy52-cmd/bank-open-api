package com.example.bank_open_api;

import org.springframework.boot.SpringApplication;

public class TestBankOpenApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(BankOpenApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
