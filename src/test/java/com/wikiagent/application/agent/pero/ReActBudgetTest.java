package com.wikiagent.application.agent.pero;

import com.wikiagent.domain.agent.PlanStep;
import com.wikiagent.domain.agent.ReActResult;
import com.wikiagent.domain.task.FatalTaskException;
import com.wikiagent.domain.task.HumanRequiredException;
import com.wikiagent.domain.task.HumanTaskKind;
import com.wikiagent.domain.task.TaskBudget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F2 任务预算单测（ReActExecutor 层）：
 * ① PAUSE_HUMAN：迭代边界超限抛 HumanRequiredException(INPUT, "预算超限")；
 * ② FAIL：超限抛 FatalTaskException(BUDGET_EXCEEDED)；
 * ③ 计量：每轮迭代 charge（+tokens/+cost/+1 iteration）并触发 sink 持久化回调；
 * ④ 记账不重置：从既有用量快照继续累计。
 */
class ReActBudgetTest {

    private ChatModel chatModel;
    private ToolExecutor toolExecutor;

    private static final PlanStep STEP = new PlanStep("step1", "检索", "tool");

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        toolExecutor = (action, ctx) -> "[obs] " + action.name();
    }

    private ReActExecutor executor() {
        return new ReActExecutor(chatModel, step -> List.of("search_knowledge_base"),
                toolExecutor, mock(TraceService.class));
    }

    /** ReAct stub：actionOnce=true 时首轮动作、次轮 FINAL；否则恒 FINAL。 */
    private void stubReAct(boolean actionOnce) {
        AtomicInteger calls = new AtomicInteger();
        when(chatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            if (actionOnce && calls.incrementAndGet() == 1) {
                return resp("{\"thought\":\"检索\",\"action\":{\"name\":\"search_knowledge_base\","
                        + "\"args\":\"{}\"},\"finalAnswer\":null}");
            }
            return resp("{\"thought\":\"结束\",\"action\":null,\"finalAnswer\":\"答案\"}");
        });
    }

    private static ReActGovernance governanceWithBudget(TaskBudget budget, List<TaskBudget> sinkLog) {
        BudgetTracker tracker = new BudgetTracker(budget, sinkLog::add);
        return new ReActGovernance(null, "pero-agent", "sess-1", tracker, java.util.Map.of());
    }

    private static ChatResponse resp(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    void pauseHumanPolicyThrowsHumanRequiredAtIterationBoundary() {
        stubReAct(true);
        // iterationLimit=1：第 1 轮 charge 后 used=1，第 2 轮边界检查超限
        TaskBudget budget = new TaskBudget(0, 8192, 0, 10, 0, 1, TaskBudget.OverflowPolicy.PAUSE_HUMAN);
        List<TaskBudget> sinkLog = new CopyOnWriteArrayList<>();

        assertThatThrownBy(() -> executor().execute(STEP, new SimplePerception("u1", "s1", "问"),
                null, 8, () -> { }, governanceWithBudget(budget, sinkLog)))
                .isInstanceOfSatisfying(HumanRequiredException.class, h -> {
                    assertThat(h.getKind()).isEqualTo(HumanTaskKind.INPUT);
                    assertThat(h.getTitle()).contains("预算超限");
                });
        // 已记账 1 轮且 sink 已持久化
        assertThat(sinkLog).isNotEmpty();
        assertThat(sinkLog.get(sinkLog.size() - 1).iterationUsed()).isEqualTo(1);
    }

    @Test
    void failPolicyThrowsFatalBudgetExceeded() {
        stubReAct(true);
        TaskBudget budget = new TaskBudget(0, 8192, 0, 10, 0, 1, TaskBudget.OverflowPolicy.FAIL);

        assertThatThrownBy(() -> executor().execute(STEP, new SimplePerception("u1", "s1", "问"),
                null, 8, () -> { }, governanceWithBudget(budget, new CopyOnWriteArrayList<>())))
                .isInstanceOfSatisfying(FatalTaskException.class, f ->
                        assertThat(f.getErrorCode().name()).isEqualTo("BUDGET_EXCEEDED"));
    }

    @Test
    void usageMeteredPerIterationAndPersistedViaSink() {
        stubReAct(true);
        TaskBudget budget = TaskBudget.defaultBudget();
        List<TaskBudget> sinkLog = new CopyOnWriteArrayList<>();

        ReActResult result = executor().execute(STEP, new SimplePerception("u1", "s1", "问"),
                null, 8, () -> { }, governanceWithBudget(budget, sinkLog));

        assertThat(result.done()).isTrue();
        // ACTION 轮 + FINAL 轮共 2 次 charge
        assertThat(sinkLog).hasSize(2);
        TaskBudget last = sinkLog.get(sinkLog.size() - 1);
        assertThat(last.iterationUsed()).isEqualTo(2);
        assertThat(last.tokenUsed()).isPositive();
        assertThat(last.costUsed()).isPositive();
    }

    @Test
    void usageContinuesFromPersistedSnapshotWithoutReset() {
        stubReAct(false); // 单轮 FINAL
        // 模拟断点恢复：payload 读回的既有用量
        TaskBudget resumed = new TaskBudget(500, 8192, 0.5, 10, 7, 20, TaskBudget.OverflowPolicy.PAUSE_HUMAN);
        List<TaskBudget> sinkLog = new CopyOnWriteArrayList<>();

        executor().execute(STEP, new SimplePerception("u1", "s1", "问"),
                null, 8, () -> { }, governanceWithBudget(resumed, sinkLog));

        assertThat(sinkLog).hasSize(1);
        TaskBudget last = sinkLog.get(0);
        // 在既有用量上累计，不重置
        assertThat(last.iterationUsed()).isEqualTo(8);
        assertThat(last.tokenUsed()).isGreaterThan(500);
        assertThat(last.costUsed()).isGreaterThan(0.5);
    }
}
