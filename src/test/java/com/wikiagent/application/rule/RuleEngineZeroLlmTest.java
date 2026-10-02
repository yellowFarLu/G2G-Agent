package com.wikiagent.application.rule;

import com.wikiagent.infrastructure.extract.LlmFieldExtractionClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * AC-D3 零 LLM 断言：规则计算全程不得触达任何 LLM 客户端。
 */
@SpringBootTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
class RuleEngineZeroLlmTest {

    @MockBean
    private LlmFieldExtractionClient llmFieldExtractionClient;

    @MockBean(name = "intentChatModel")
    private org.springframework.ai.chat.model.ChatModel intentChatModel;

    @MockBean(name = "simpleChatModel")
    private org.springframework.ai.chat.model.ChatModel simpleChatModel;

    @MockBean(name = "complexChatModel")
    private org.springframework.ai.chat.model.ChatModel complexChatModel;

    @Autowired
    private RuleSetService ruleSetService;

    @Autowired
    private RuleExecutionService executionService;

    @Test
    void computeNeverCallsLlm() {
        String code = "zero-llm-" + System.nanoTime();
        String dsl = """
                {"steps":[
                  {"op":"const","params":{"value":1},"to":"one"},
                  {"op":"arith","params":{"expr":"one + 1"},"to":"two"}
                ],"outputs":["two"]}
                """;
        ruleSetService.createDraft(code, dsl, "零LLM测试", "test");
        ruleSetService.publish(code, 1);

        var comp = executionService.execute(code, null, Map.of());
        assertThat(comp.status().name()).isEqualTo("SUCCESS");

        verifyNoInteractions(llmFieldExtractionClient);
        verifyNoInteractions(intentChatModel);
        verifyNoInteractions(simpleChatModel);
        verifyNoInteractions(complexChatModel);
    }
}
