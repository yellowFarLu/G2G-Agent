package com.wikiagent.infrastructure.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * v1-v2 §10 "Rule of Two" 状态机 - 限制每个 conversation 的工具与 LLM 调用次数。
 * <p>
 * 规则：每个 conversation（conversationId）内：
 * <ul>
 *   <li>最多 2 次工具调用（canCallTool / recordToolCall）</li>
 *   <li>最多 2 次 LLM 调用（canCallLlm / recordLlmCall）</li>
 * </ul>
 * 超过 2 次时返回 false，阻止后续调用，防止无限循环与资源耗尽。
 * <p>
 * 开关：{@code wikiagent.security.rule-of-two-enabled=true}（默认开启）。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.security.rule-of-two-enabled",
        havingValue = "true", matchIfMissing = true)
public class RuleOfTwoStateMachine {

    /** 每个 conversation 最多允许的工具调用次数。 */
    private static final int MAX_TOOL_CALLS = 2;

    /** 每个 conversation 最多允许的 LLM 调用次数。 */
    private static final int MAX_LLM_CALLS = 2;

    private final ConcurrentMap<String, Integer> toolCallCounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> llmCallCounts = new ConcurrentHashMap<>();

    /** 判断当前 conversation 是否还能调用工具。 */
    public boolean canCallTool(String conversationId) {
        return toolCallCounts.getOrDefault(conversationId, 0) < MAX_TOOL_CALLS;
    }

    /** 判断当前 conversation 是否还能调用 LLM。 */
    public boolean canCallLlm(String conversationId) {
        return llmCallCounts.getOrDefault(conversationId, 0) < MAX_LLM_CALLS;
    }

    /** 记录一次工具调用（计数 +1）。 */
    public void recordToolCall(String conversationId) {
        toolCallCounts.merge(conversationId, 1, Integer::sum);
    }

    /** 记录一次 LLM 调用（计数 +1）。 */
    public void recordLlmCall(String conversationId) {
        llmCallCounts.merge(conversationId, 1, Integer::sum);
    }

    /** 重置指定 conversation 的计数（会话结束或人工介入时调用）。 */
    public void reset(String conversationId) {
        toolCallCounts.remove(conversationId);
        llmCallCounts.remove(conversationId);
    }
}
