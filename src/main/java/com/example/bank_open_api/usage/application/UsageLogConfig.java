package com.example.bank_open_api.usage.application;

// [무엇] 사용량 기록 설정 등록
// [역할] usage-log.* 설정값(UsageLogProperties)을 쓸 수 있게 켠다
// [누가 쓰나] Spring이 서버 시작 때 읽음

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(UsageLogProperties.class)
public class UsageLogConfig {
}
