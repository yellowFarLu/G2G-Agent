package com.wikiagent.application.agent.pero;

import com.wikiagent.application.llm.ModelPricingService;
import com.wikiagent.domain.agent.PlanStep;
import com.wikiagent.domain.task.TaskBudget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #17 ReAct 真实 token 计量回归（surefire 单测）：
 * <ul>
 *   <li>响应 metadata 携带 DefaultUsage(10,20)：tokenUsed 按 10+20=30 实计，
 *       无 pricing 时 cost=0；</li>
 *   <li>无 metadata：回退 4 字符≈1 token 估算，不报错且成本走标称常量；</li>
 *   <li>有 ModelPricingService 配置：成本按模型单价计算（1.0/2.0 元每 1K → 0.05）。</li>
 * </ul>
 */
class ReActUsageMeteringTest {

    private static final PlanStep STEP = new PlanStep("step1", "检索", "tool");
    private static final String FINAL_JSON =
            "{\"thought\":\"结束\",\"action\":null,\"finalAnswer\":\"答案\"}";

    private ChatModel chatModel;
    private ToolExecutor toolExecutor;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        toolExecutor = (action, ctx) -> "[obs]";
    }

    private ReActGovernance budgetGovernance(List<TaskBudget> sinkLog) {
        return new ReActGovernance(null, "pero-agent", "sess-1",
                new BudgetTracker(TaskBudget.defaultBudget(), sinkLog::add), java.util.Map.of());
    }

    private ChatResponse response(ChatResponseMetadata metadata) {
        List<Generation> generations = List.of(new Generation(new AssistantMessage(FINAL_JSON)));
        return metadata == null ? new ChatResponse(generations) : new ChatResponse(generations, metadata);
    }

    @Test
    void realUsageMeteredFromMetadataThirtyTokens() {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model("qwen-test")
                .usage(new DefaultUsage(10, 20))
                .build();
        when(chatModel.call(any(Prompt.class))).thenReturn(response(metadata));

        List<TaskBudget> sinkLog = new CopyOnWriteArrayList<>();
        executor(false, null).execute(STEP, new SimplePerception("u1", "s1", "问"),
                null, 8, () -> { }, budgetGovernance(sinkLog));

        TaskBudget last = sinkLog.get(sinkLog.size() - 1);
        assertThat(last.tokenUsed()).as("真实 usage：prompt 10 + completion 20 = 30").isEqualTo(30);
        assertThat(last.iterationUsed()).isEqualTo(1);
        assertThat(last.costUsed()).as("未注入 ModelPricingService 时成本记 0").isEqualTo(0d);
    }

    @Test
    void missingMetadataFallsBackToEstimateWithoutError() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response(null));

        List<TaskBudget> sinkLog = new CopyOnWriteArrayList<>();
        executor(false, null).execute(STEP, new SimplePerception("u1", "s1", "问"),
                null, 8, () -> { }, budgetGovernance(sinkLog));

        TaskBudget last = sinkLog.get(sinkLog.size() - 1);
        assertThat(last.tokenUsed()).as("无 usage 时回退字符估算，至少计 1 token").isPositive();
        assertThat(last.costUsed()).as("估算路径沿用标称单价，成本为正").isPositive();
    }

    @Test
    @SuppressWarnings("unchecked")
    void realUsagePricedByModelPricingService() {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model("qwen-test")
                .usage(new DefaultUsage(10, 20))
                .build();
        when(chatModel.call(any(Prompt.class))).thenReturn(response(metadata));

        Environment env = mock(Environment.class);
        when(env.getProperty("wikiagent.llm.pricing.qwen-test")).thenReturn("1.0:2.0");
        ObjectProvider<ModelPricingService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(new ModelPricingService(env));

        List<TaskBudget> sinkLog = new CopyOnWriteArrayList<>();
        executor(true, provider).execute(STEP, new SimplePerception("u1", "s1", "问"),
                null, 8, () -> { }, budgetGovernance(sinkLog));

        TaskBudget last = sinkLog.get(sinkLog.size() - 1);
        assertThat(last.tokenUsed()).isEqualTo(30);
        // 10/1000*1.0 + 20/1000*2.0 = 0.01 + 0.04
        assertThat(last.costUsed()).isEqualTo(0.05, org.assertj.core.data.Offset.offset(1e-9));
    }

    private ReActExecutor executor(boolean withPricing,
                                   ObjectProvider<ModelPricingService> provider) {
        if (!withPricing) {
            return new ReActExecutor(chatModel, step -> List.of("search_knowledge_base"),
                    toolExecutor, mock(TraceService.class));
        }
        return new ReActExecutor(chatModel, step -> List.of("search_knowledge_base"),
                toolExecutor, mock(TraceService.class), null, null, provider);
    }
}
