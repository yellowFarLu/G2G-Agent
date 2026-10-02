package com.wikiagent.infrastructure.observability.degradation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-I4 ④ LLM 不可用（无 API Key → 候选 provider 全不可用/NoOp 兜底）：
 * POST /api/chat 不返回 5xx，SSE 通道正常建立（降级为兜底空回答而非接口错误）。
 * 降级链行为本身由 LlmChainDegradationTest 做确定性单测，本测试补 HTTP 层证据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class LlmUnavailableChatIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:file:./data/h2/test-llm-degrade-" + System.nanoTime()
                        + ";AUTO_SERVER=TRUE;MODE=MySQL;LOCK_TIMEOUT=10000");
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void 无模型Key时chat接口不返回5xx且SSE通道建立() throws Exception {
        // 默认环境无 DASHSCOPE_API_KEY：候选 provider available=false，链尾 NoOp 空响应兜底
        var result = mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"你好\"}"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        // SSE 通道：text/event-stream（允许 ChatStreamer 实现选择具体 charset 后缀）
        assertThat(result.getResponse().getContentType()).contains("text/event-stream");
    }
}
