package com.wikiagent.infrastructure.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 子项目 J（AC-J3）：ChatModel 装饰器——在<b>真正外发前</b>对 Prompt 中
 * USER/SYSTEM 文本执行 {@link PiiMinimizer}。
 * <p>
 * 不修改 AgentRagService / Provider 链等既有文件，而由
 * {@link PiiMinimizationConfiguration} 以 BeanPostProcessor 方式后置包装所有 ChatModel Bean，
 * 对业务代码透明。
 * <ul>
 *   <li>MASK：以脱敏后的 Prompt 调用下游模型（媒体/元数据经 {@code mutate()} 保留）；</li>
 *   <li>BLOCK：不触达下游，{@code call} 直接返回固定拒答、{@code stream} 以单条拒答完成流，
 *       保证外发文本中绝不出现原始 PII；</li>
 *   <li>命中只记录类别/掩码片段（SLF4J + {@link AuditSink}），<b>不记录原始 PII</b>。</li>
 * </ul>
 * ASSISTANT/工具消息原样透传（对话历史中的外发 PII 主要来自 USER；LLM 输出侧由输出网关负责）。
 */
public class PiiMinimizingChatModelDecorator implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(PiiMinimizingChatModelDecorator.class);

    private final ChatModel delegate;
    private final PiiMinimizer minimizer;
    private final AuditSink auditSink;

    public PiiMinimizingChatModelDecorator(ChatModel delegate, PiiMinimizer minimizer) {
        this(delegate, minimizer, AuditSink.NOOP);
    }

    public PiiMinimizingChatModelDecorator(ChatModel delegate, PiiMinimizer minimizer, AuditSink auditSink) {
        this.delegate = delegate;
        this.minimizer = minimizer;
        this.auditSink = auditSink == null ? AuditSink.NOOP : auditSink;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        MinimizationOutcome outcome = minimizePrompt(prompt);
        if (outcome == null) {
            return delegate.call(prompt);
        }
        if (outcome.blocked()) {
            return refusalResponse(outcome);
        }
        return delegate.call(outcome.prompt());
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        MinimizationOutcome outcome = minimizePrompt(prompt);
        if (outcome == null) {
            return delegate.stream(prompt);
        }
        if (outcome.blocked()) {
            return Flux.just(refusalResponse(outcome));
        }
        return delegate.stream(outcome.prompt());
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    /** 被包装的真实模型（需要穿透装饰器取底层配置时使用）。 */
    public ChatModel unwrap() {
        return delegate;
    }

    private static ChatResponse refusalResponse(MinimizationOutcome outcome) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(outcome.refusal()))));
    }

    /**
     * @return null 表示无任何命中（原样透传，避免不必要的 Prompt 重建）；
     *         否则为最小化结果（BLOCK 时 prompt 为 null，下游不会被调用）
     */
    private MinimizationOutcome minimizePrompt(Prompt prompt) {
        List<Message> instructions = prompt.getInstructions();
        if (instructions == null || instructions.isEmpty()) {
            return null;
        }
        List<Message> maskedMessages = new ArrayList<>(instructions.size());
        boolean changed = false;
        boolean blocked = false;
        List<PiiMinimizer.Finding> findings = new ArrayList<>();

        for (Message message : instructions) {
            if (message.getMessageType() == null) {
                maskedMessages.add(message);
                continue;
            }
            switch (message.getMessageType()) {
                case USER -> {
                    UserMessage userMessage = (UserMessage) message;
                    PiiMinimizer.Result r = minimizer.minimize(userMessage.getText());
                    if (!r.findings().isEmpty()) {
                        findings.addAll(r.findings());
                        audit(r);
                    }
                    if (r.blocked()) {
                        blocked = true;
                    } else if (!r.text().equals(userMessage.getText())) {
                        changed = true;
                        maskedMessages.add(userMessage.mutate().text(r.text()).build());
                    } else {
                        maskedMessages.add(message);
                    }
                }
                case SYSTEM -> {
                    SystemMessage systemMessage = (SystemMessage) message;
                    PiiMinimizer.Result r = minimizer.minimize(systemMessage.getText());
                    if (!r.findings().isEmpty()) {
                        findings.addAll(r.findings());
                        audit(r);
                    }
                    if (r.blocked()) {
                        blocked = true;
                    } else if (!r.text().equals(systemMessage.getText())) {
                        changed = true;
                        maskedMessages.add(systemMessage.mutate().text(r.text()).build());
                    } else {
                        maskedMessages.add(message);
                    }
                }
                default -> maskedMessages.add(message);
            }
        }

        if (blocked) {
            String categories = categoriesOf(findings);
            log.warn("PII 外发阻断 categories={}（原始文本不记录）", categories);
            return MinimizationOutcome.blocked(
                    "抱歉，您的输入包含不允许外发的敏感信息（类别：" + categories
                            + "），本次请求已被 PII 最小化策略阻断，请去除相关信息后重试。",
                    findings);
        }
        if (!changed) {
            return null;
        }
        log.info("PII 外发掩码 categories={}（原始文本不记录）", categoriesOf(findings));
        return MinimizationOutcome.masked(new Prompt(maskedMessages, prompt.getOptions()), findings);
    }

    private void audit(PiiMinimizer.Result result) {
        try {
            auditSink.record(result.blocked() ? AuditAction.BLOCK : AuditAction.MASK,
                    result.findings());
        } catch (Exception e) {
            log.debug("PII 审计回调失败（不影响主链路）: {}", e.getMessage());
        }
    }

    private static String categoriesOf(List<PiiMinimizer.Finding> findings) {
        return findings.stream()
                .map(f -> f.category().name())
                .distinct()
                .collect(Collectors.joining(","));
    }

    /** 最小化处置动作（审计用）。 */
    public enum AuditAction {MASK, BLOCK}

    /** 审计回调：只接收类别/掩码信息，禁止在实现中回拼原始 PII。 */
    @FunctionalInterface
    public interface AuditSink {
        AuditSink NOOP = (action, findings) -> { };

        void record(AuditAction action, List<PiiMinimizer.Finding> findings);
    }

    /** 内部传递对象。 */
    private record MinimizationOutcome(Prompt prompt, boolean blocked, String refusal,
                                       List<PiiMinimizer.Finding> findings) {
        static MinimizationOutcome masked(Prompt prompt, List<PiiMinimizer.Finding> findings) {
            return new MinimizationOutcome(prompt, false, null, findings);
        }

        static MinimizationOutcome blocked(String refusal, List<PiiMinimizer.Finding> findings) {
            return new MinimizationOutcome(null, true, refusal, findings);
        }
    }
}
