package com.example.bank_open_api.mockfintech;

// [무엇] 모의 핀테크 서버가 꺼져 있을 때(기본값) 테스트
// [역할] mock-fintech.enabled=false면 /mock-fintech/** 가 어떤 메서드로든 404 (401이 아니라 "없는 주소")
//        → 운영에서 인증 없는 실험용 API가 열려 있지 않음을 확인
// [누가 쓰나] ./gradlew test (Docker 필요)
//
// 설정이 WebhookApiTest와 같아서 스프링 테스트 환경을 함께 쓴다 (컨테이너를 새로 띄우지 않음)

import com.example.bank_open_api.TestcontainersConfiguration;
import com.example.bank_open_api.mockfintech.MockFintechReceiver;
import com.example.bank_open_api.support.TestClockConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "jwt.secret=test-only-jwt-secret-key-0123456789abcdef")
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
class MockFintechDisabledTest {

    @Autowired WebApplicationContext context;
    @Autowired ApplicationContext applicationContext;

    @Test
    void 꺼져_있으면_모든_mock_fintech_주소가_404이고_빈도_없다() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        mockMvc.perform(post("/mock-fintech/webhooks").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/mock-fintech/received")).andExpect(status().isNotFound());
        mockMvc.perform(put("/mock-fintech/config").contentType(MediaType.APPLICATION_JSON).content("{\"secret\":\"x\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/mock-fintech/received")).andExpect(status().isNotFound());

        assertThat(applicationContext.getBeanNamesForType(MockFintechReceiver.class)).isEmpty();
    }
}
