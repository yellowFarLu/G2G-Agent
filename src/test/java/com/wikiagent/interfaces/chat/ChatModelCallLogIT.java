package com.wikiagent.interfaces.chat;

import com.wikiagent.domain.llm.ModelCallLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E4 MockMvc chat 打点集成测试：POST /api/chat 后 model_call_log 行数 ≥ 1
 * （无 API Key 环境走 NoOp 降级链，仍应落 INTENT 打点）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
class ChatModelCallLogIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ModelCallLogRepository callLogRepo;

    @Test
    void chat后model_call_log至少落一行() throws Exception {
        long before = callLogRepo.count();

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"你好\"}"))
                .andExpect(status().isOk());

        // chat 为 @Async：轮询等待打点落库（NoOp 链路毫秒级完成）
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (callLogRepo.count() > before) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(callLogRepo.count()).isGreaterThan(before);
    }
}
