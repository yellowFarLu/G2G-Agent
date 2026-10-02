package com.wikiagent.infrastructure.security;

import com.wikiagent.infrastructure.gateway.GuardrailAdvisorChain;
import com.wikiagent.service.chat.SseSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 子项目 J（AC-J2）：流式输出安全网关装饰器。
 * <p>
 * 包装在最外层 SSE 通道上：累积 {@code delta} 文本，在链路准备发送 {@code done} 之前，
 * 对<b>完整累积答案</b>执行 {@link GuardrailAdvisorChain#checkOutput} 输出检测：
 * <ul>
 *   <li>通过：才转发 {@code done}；若检测器链产出了脱敏文本（SANITIZE），
 *       以脱敏后文本作为最终落地内容交给 {@link AnswerFinalizer}（已发出的 delta 受 SSE
 *       本质限制不可撤回，前端最终态以 done 前拦截 + 落库脱敏为准）；</li>
 *   <li>违规：不转发 {@code done}，改写为 {@code blocked} 事件（沿用既有
 *       reason/violationType 载荷并补充 detector/phase 字段），complete 结束流，
 *       并经 {@link AnswerFinalizer} 标记 blocked（答案不允许以 assistant 身份落库）。</li>
 * </ul>
 * 阻断计数/审计由 {@link GuardrailAdvisorChain#checkOutput} 复用既有
 * GatewayAuditService（gateway_audit_log / content_violation_log）完成，本类不另建计数。
 * <p>
 * 非流式路径（AgentOrchestrator 在发 delta 前已整体检测）不受影响：本装饰器对
 * 已检测内容幂等放行；空答案（无 delta）保持历史行为直接转发 done。
 */
public class StreamingOutputGuardrailSender extends SseSender {

    private static final Logger log = LoggerFactory.getLogger(StreamingOutputGuardrailSender.class);

    /** blocked 载荷中的检测器标识（ChainResult 不携带具体 detector 名，统一标记网关链）。 */
    public static final String DETECTOR = "output_guardrail_chain";

    private final SseSender delegate;
    private final GuardrailAdvisorChain guardrailChain;
    private final String userId;
    private final String sessionId;
    private final AnswerFinalizer finalizer;
    private final StringBuilder accumulated = new StringBuilder();
    private final AtomicBoolean finished = new AtomicBoolean(false);

    public StreamingOutputGuardrailSender(SseSender delegate,
                                          GuardrailAdvisorChain guardrailChain,
                                          String userId, String sessionId,
                                          AnswerFinalizer finalizer) {
        // 自身不持有 emitter，全部方法委托给被包装的 SseSender（基类为此提供 protected 构造器）
        super();
        this.delegate = delegate;
        this.guardrailChain = guardrailChain;
        this.userId = userId;
        this.sessionId = sessionId;
        this.finalizer = finalizer;
    }

    @Override
    public boolean send(String event, Object data) {
        if ("delta".equals(event) && data instanceof Map<?, ?> map) {
            Object text = map.get("text");
            if (text != null) {
                accumulated.append(text);
            }
            return delegate.send(event, data);
        }

        if ("done".equals(event)) {
            // done 只处理一次（防御上游重复发送）
            if (!finished.compareAndSet(false, true)) {
                log.debug("重复 done 事件已忽略 sessionId={}", sessionId);
                return true;
            }
            String fullAnswer = accumulated.toString();
            if (fullAnswer.isBlank()) {
                // 无文本回答：保持历史行为（不持久化空 assistant），直接放行
                return delegate.send(event, data);
            }

            GuardrailAdvisorChain.ChainResult result =
                    guardrailChain.checkOutput(fullAnswer, userId, sessionId);
            if (!result.passed()) {
                return handleBlocked(result);
            }
            // 检测器链可能产出脱敏文本（SANITIZE 后链尾 passed）：落地以脱敏文本为准
            String finalAnswer = result.content() == null ? fullAnswer : result.content();
            if (finalizer != null) {
                finalizer.onAnswer(sessionId, finalAnswer);
            }
            return delegate.send(event, data);
        }

        return delegate.send(event, data);
    }

    private boolean handleBlocked(GuardrailAdvisorChain.ChainResult result) {
        String reason = result.blockedReason() == null ? "输出内容不合规" : result.blockedReason();
        String violationType = result.violationType() == null ? "UNKNOWN" : result.violationType();
        log.warn("流式输出被安全网关拦截，不发送 done sessionId={} type={} reason={}",
                sessionId, violationType, reason);
        if (finalizer != null) {
            finalizer.onBlocked(sessionId, violationType, reason);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", reason);
        payload.put("violationType", violationType);
        payload.put("detector", DETECTOR);
        payload.put("phase", "streaming_output");
        delegate.send("blocked", payload);
        delegate.complete();
        return true;
    }

    @Override
    public void complete() {
        delegate.complete();
    }

    /**
     * 最终答案落地回调：放行时持久化（可能已脱敏的）最终答案；
     * 拦截时写 blocked 标记，违规答案不得以 assistant 身份进入历史。
     */
    public interface AnswerFinalizer {
        void onAnswer(String sessionId, String finalAnswer);

        void onBlocked(String sessionId, String violationType, String reason);
    }
}
