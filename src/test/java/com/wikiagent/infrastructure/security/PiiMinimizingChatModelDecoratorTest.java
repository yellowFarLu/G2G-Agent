package com.wikiagent.infrastructure.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AC-J3 装饰器单测：MASK 时外发 Prompt 不含原始 PII 且对话可继续；
 * BLOCK 时 call/stream 都不触达下游模型而返回固定拒答；审计回调只收到类别/掩码。
 */
class PiiMinimizingChatModelDecoratorTest {

    private ChatModel downstream;
    private final List<PiiMinimizingChatModelDecorator.AuditAction> auditActions = new ArrayList<>();
    private final List<PiiMinimizer.Finding> auditFindings = new ArrayList<>();

    private final PiiMinimizingChatModelDecorator.AuditSink auditSink = (action, findings) -> {
        auditActions.add(action);
        auditFindings.addAll(findings);
    };

    @BeforeEach
    void setUp() {
        downstream = mock(ChatModel.class);
        auditActions.clear();
        auditFindings.clear();
    }

    private PiiMinimizingChatModelDecorator maskDecorator() {
        return new PiiMinimizingChatModelDecorator(downstream, PiiMinimizer.maskAll(), auditSink);
    }

    @Test
    void call的mask路径外发prompt不含原始手机号且保留对话文本() {
        when(downstream.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(
                        new AssistantMessage("好的，已通过 138****8000 与您联系（掩码确认）")))));

        Prompt prompt = new Prompt(List.of(
                new SystemMessage("你是客服助手"),
                UserMessage.builder().text("我的手机13800138000打不通，帮我查工单").build()));

        ChatResponse response = maskDecorator().call(prompt);

        ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
        verify(downstream).call(sent.capture());
        String outbound = sent.getValue().getInstructions().stream()
                .map(m -> m.getText()).reduce("", String::concat);
        assertThat(outbound)
                .doesNotContain("13800138000")
                .contains("138****8000")
                .contains("打不通，帮我查工单"); // 可对话性：周边语义保留
        assertThat(response.getResult().getOutput().getText()).contains("掩码确认");
        assertThat(auditActions).containsExactly(PiiMinimizingChatModelDecorator.AuditAction.MASK);
        assertThat(auditFindings).hasSize(1);
        assertThat(auditFindings.get(0).category()).isEqualTo(PiiMinimizer.PiiCategory.PHONE);
        assertThat(auditFindings.get(0).masked()).isEqualTo("138****8000");
    }

    @Test
    void call的block路径不触达下游而返回固定拒答() {
        PiiMinimizer blocker = new PiiMinimizer(
                Map.of(PiiMinimizer.PiiCategory.PHONE, PiiMinimizer.Policy.BLOCK));
        PiiMinimizingChatModelDecorator decorator =
                new PiiMinimizingChatModelDecorator(downstream, blocker, auditSink);

        ChatResponse response = decorator.call(new Prompt(List.of(
                UserMessage.builder().text("回电号码 13800138000").build())));

        verifyNoInteractions(downstream);
        assertThat(response.getResult().getOutput().getText())
                .contains("PII 最小化策略阻断")
                .contains("PHONE")
                .doesNotContain("13800138000");
        assertThat(auditActions).containsExactly(PiiMinimizingChatModelDecorator.AuditAction.BLOCK);
    }

    @Test
    void stream的mask路径外发脱敏prompt() {
        when(downstream.stream(any(Prompt.class))).thenReturn(reactor.core.publisher.Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("收到掩码号码"))))));

        maskDecorator().stream(new Prompt(List.of(
                UserMessage.builder().text("手机15912345678收不到短信").build()))).blockLast();

        ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
        verify(downstream).stream(sent.capture());
        assertThat(sent.getValue().getInstructions().get(0).getText())
                .doesNotContain("15912345678")
                .contains("159****5678");
    }

    @Test
    void stream的block路径返回单条拒答且不触达下游() {
        PiiMinimizer blocker = new PiiMinimizer(
                Map.of(PiiMinimizer.PiiCategory.EMAIL, PiiMinimizer.Policy.BLOCK));
        PiiMinimizingChatModelDecorator decorator =
                new PiiMinimizingChatModelDecorator(downstream, blocker, auditSink);

        ChatResponse response = decorator.stream(new Prompt(List.of(
                UserMessage.builder().text("发我邮箱 zhang.san@example.com").build())))
                .blockLast();

        verify(downstream, never()).stream(any(Prompt.class));
        verify(downstream, never()).call(any(Prompt.class));
        assertThat(response.getResult().getOutput().getText())
                .contains("PII 最小化策略阻断")
                .contains("EMAIL")
                .doesNotContain("zhang.san@example.com");
    }

    @Test
    void 无pii时直接透传原prompt且无审计事件() {
        when(downstream.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
        Prompt prompt = new Prompt(List.of(
                UserMessage.builder().text("PMS 退货流程怎么走").build()));

        maskDecorator().call(prompt);

        verify(downstream).call(prompt); // 同一对象透传
        assertThat(auditActions).isEmpty();
        assertThat(auditFindings).isEmpty();
    }

    @Test
    void 非user与system消息原样透传() {
        when(downstream.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
        Prompt prompt = new Prompt(List.of(
                UserMessage.builder().text("问题").build(),
                new AssistantMessage("历史回复里有 13800138000")));

        maskDecorator().call(prompt);

        ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
        verify(downstream).call(sent.capture());
        // ASSISTANT 消息不经最小化（输出侧网关负责）
        assertThat(sent.getValue().getInstructions().get(1).getText())
                .isEqualTo("历史回复里有 13800138000");
    }
}
