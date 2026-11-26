package com.example.bank_open_api.webhook.config;

// [무엇] 웹훅 발송 일꾼을 주기적으로 깨우는 시계
// [역할] webhook.dispatch.interval(기본 1초)마다 WebhookDispatcher.dispatchDue()를 실행한다
//        fixed delay: 앞 실행이 "끝난 뒤" 1초 쉬고 다음 실행 → 한 서버 안에서는 일꾼이 겹쳐 돌지 않는다
//        (서버가 여러 대면 서버마다 일꾼이 돌지만, SKIP LOCKED 덕분에 같은 알림을 둘이 보내지 않는다)
// [누가 쓰나] Spring (webhook.dispatch.enabled=true일 때만 이 설정이 켜진다)
//
// 테스트에서는 src/test/resources/config/application.properties에서 enabled=false로 꺼 두고,
// 필요한 테스트만 dispatcher.dispatchDue()를 직접 부른다 (언제 보내는지 테스트가 정확히 정할 수 있게)

import com.example.bank_open_api.webhook.application.WebhookDispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "webhook.dispatch.enabled", havingValue = "true", matchIfMissing = true)
public class WebhookDispatchScheduler implements SchedulingConfigurer {

    private final WebhookDispatcher dispatcher;
    private final WebhookProperties properties;

    public WebhookDispatchScheduler(WebhookDispatcher dispatcher, WebhookProperties properties) {
        this.dispatcher = dispatcher;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(dispatcher::dispatchDue, properties.dispatch().interval());
    }
}
