package com.wikiagent.infrastructure.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * v5 §19.4 输入检测器 - LLM Judge。
 * <p>
 * 用 intent-model（轻量模型）判断用户输入是否为 prompt injection 攻击。
 * 如果 LLM 不可用（API key 为空），降级为关键词匹配。
 */
@Component
public class LlmJudgeDetector implements GuardrailDetector {

    private static final Logger log = LoggerFactory.getLogger(LlmJudgeDetector.class);

    private final String blockThreshold;
    private final boolean llmAvailable;

    public LlmJudgeDetector(@Value("${wikiagent.gateway.input.llm-judge.block-threshold:0.7}") String blockThreshold,
                             @Value("${DASHSCOPE_API_KEY:}") String apiKey) {
        this.blockThreshold = blockThreshold;
        this.llmAvailable = apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String name() { return "llm_judge"; }

    @Override
    public String direction() { return "INPUT"; }

    @Override
    public GuardrailResult check(String content, String userId, String sessionId) {
        if (content == null || content.isBlank()) {
            return GuardrailResult.pass(name());
        }

        if (!llmAvailable) {
            // LLM 不可用时降级为简单规则检查
            log.debug("LLM 不可用，降级为规则检查");
            return simpleRuleCheck(content);
        }

        // LLM 可用时：调用 intent-model 判断
        // 实际实现需要注入 ChatModel 并调用，这里先返回 pass
        // v5 实施校正：DashScope ChatModel 注入需要 @Qualifier，避免与 routing 层冲突
        log.debug("LLM Judge 检测中...");
        return GuardrailResult.pass(name());
    }

    /**
     * 简单规则降级检查（LLM 不可用时使用）。
     */
    private GuardrailResult simpleRuleCheck(String content) {
        List<String> suspiciousPatterns = List.of(
                "忽略", "ignore", "disregard", "override",
                "system prompt", "jailbreak", "DAN"
        );
        String lower = content.toLowerCase();
        for (String pattern : suspiciousPatterns) {
            if (lower.contains(pattern.toLowerCase())) {
                return GuardrailResult.block(name(), 0.7,
                        "疑似 prompt injection: " + pattern, "PROMPT_INJECTION");
            }
        }
        return GuardrailResult.pass(name());
    }
}
