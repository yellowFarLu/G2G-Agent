package com.wikiagent.application.agent.pero;

import com.wikiagent.domain.agent.Plan;
import com.wikiagent.domain.agent.PlanStep;
import com.wikiagent.domain.agent.Reflection;
import com.wikiagent.domain.task.PauseSignalException;
import com.wikiagent.service.chat.SseSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 12 PERO 循环钩子测试：
 * ① hook.beforeNode 每节点调用一次（次数=节点数），NOOP gate 下循环正常走完；
 * ② gate 在第 2 次 ReAct 迭代抛 PauseSignalException 时原样穿出 executeLoop，
 *    不得被节点通用 catch 吞掉（handover.failNode/completeNode 均不触发）。
 */
class PeroLoopHooksTest {

    private ChatModel chatModel;
    private Handover handover;
    private PeroAgent peroAgent;
    private SseSender sse;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        handover = mock(Handover.class);
        sse = new SseSender(new SseEmitter());
        AtomicInteger reActCalls = new AtomicInteger();
        when(chatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            if (reActCalls.incrementAndGet() == 1) {
                return chatResponse("{\"thought\":\"检索\",\"action\":{\"name\":\"search_knowledge_base\","
                        + "\"args\":\"商家入驻\"},\"finalAnswer\":null}");
            }
            return chatResponse("{\"thought\":\"已获得答案\",\"action\":null,"
                    + "\"finalAnswer\":\"商家入驻流程答案\"}");
        });

        ToolRegistry registry = step -> List.of("search_knowledge_base");
        ToolExecutor toolExecutor = (action, ctx) -> "[obs] " + action.name();
        TraceService trace = mock(TraceService.class);
        Reflector reflector = mock(Reflector.class);
        when(reflector.reflect(any(), any(), any())).thenAnswer(inv ->
                Reflection.of(((PlanStep) inv.getArgument(0)).id(), "ok", false, null, List.of()));
        EpisodicMemory memory = mock(EpisodicMemory.class);
        Optimizer optimizer = (remaining, reflection) -> remaining;
        Generator generator = (ctx, h) -> "最终答案";

        peroAgent = new PeroAgent(mock(PeroPlanner.class),
                new ReActExecutor(chatModel, registry, toolExecutor, trace),
                reflector, optimizer, memory, trace, handover, generator, 8);
    }

    @Test
    void beforeNodeCalledForEachNodeAndLoopCompletesUnderNoopGate() {
        Plan plan = new Plan(List.of(
                new PlanStep("step1", "检索商家入驻流程", "search_kb"),
                new PlanStep("step2", "汇总生成答案", "generate")));
        List<PlanStep> seenNodes = new ArrayList<>();
        PeroLoopHook hook = new PeroLoopHook() {
            @Override
            public void beforeNode(PlanStep step, int index) {
                seenNodes.add(step);
            }
        };

        peroAgent.executeLoop(new SimplePerception("u1", "s1", "商家入驻流程是什么"),
                plan, handover, sse, hook);

        assertThat(seenNodes).extracting(PlanStep::id).containsExactly("step1", "step2");
        verify(handover, times(2)).completeNode(any(), any());
        verify(handover, never()).failNode(any(), any());
    }

    @Test
    void pauseSignalFromSecondIterationPropagatesWithoutFailNode() {
        Plan plan = new Plan(List.of(new PlanStep("step1", "检索商家入驻流程", "search_kb")));
        AtomicInteger gateCalls = new AtomicInteger();
        PeroLoopHook hook = new PeroLoopHook() {
            @Override
            public void afterReactIteration(PlanStep step, int iter) {
                if (gateCalls.incrementAndGet() == 2) {
                    throw new PauseSignalException();
                }
            }
        };

        assertThatThrownBy(() -> peroAgent.executeLoop(new SimplePerception("u1", "s1", "问"),
                plan, handover, sse, hook))
                .isInstanceOf(PauseSignalException.class);

        verify(handover, never()).failNode(any(), any());
        verify(handover, never()).completeNode(any(), any());
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
