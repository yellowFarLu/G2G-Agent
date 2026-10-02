package com.wikiagent.application.prompt;

import com.wikiagent.domain.llm.ModelCallLog;
import com.wikiagent.domain.llm.ModelCallLogPurpose;
import com.wikiagent.domain.llm.ModelCallLogRepository;
import com.wikiagent.domain.prompt.PromptTemplate;
import com.wikiagent.domain.prompt.PromptTemplateRepository;
import com.wikiagent.domain.prompt.RenderedPrompt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E1 持久层集成测试：V14 prompt_template / model_call_log 在 H2 MODE=MySQL 真实迁移；
 * ACTIVE 模板查询、code+version 唯一约束、model_call_log 落库。
 */
@SpringBootTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
class PromptTemplatePersistenceIT {

    @Autowired
    private PromptTemplateRepository templateRepo;

    @Autowired
    private ModelCallLogRepository callLogRepo;

    @Test
    void promptTemplateActiveQueryAndUniqueConstraint() {
        String code = "it-" + System.nanoTime();
        templateRepo.save(new PromptTemplate(null, code, 1, "v1 内容 {x}",
                PromptTemplate.Status.ACTIVE, null, null));

        Optional<RenderedPrompt> hit = new PromptTemplateService(templateRepo)
                .render(code, Map.of("x", "渲染"));
        assertThat(hit).isPresent();
        assertThat(hit.get().content()).isEqualTo("v1 内容 渲染");
        assertThat(hit.get().version()).isEqualTo(1);

        // 同 code 不同版本可共存；同 code+version 重复 → 唯一约束冲突
        assertThatThrownBy(() -> templateRepo.save(new PromptTemplate(null, code, 1, "重复",
                PromptTemplate.Status.DRAFT, null, null)))
                .isInstanceOf(Exception.class);
    }

    @Test
    void modelCallLogRoundTrip() {
        ModelCallLog saved = callLogRepo.save(ModelCallLog.builder()
                .traceId("trace-it")
                .userId("u1")
                .sessionId("s1")
                .purpose(ModelCallLogPurpose.CHAT)
                .provider("dashscope")
                .model("qwen-plus")
                .tokensIn(100).tokensOut(200)
                .costEstimate(0.004)
                .latencyMs(320L)
                .status(ModelCallLog.Status.OK)
                .build());
        assertThat(saved.id()).isNotNull();
        assertThat(callLogRepo.findByTraceId("trace-it")).hasSize(1);
        assertThat(callLogRepo.count()).isGreaterThanOrEqualTo(1);
    }
}
