package com.wikiagent.infrastructure.gateway;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * v5 §19.4 输入检测器 - 关键词黑名单。
 * <p>
 * 检测用户输入中是否包含 prompt injection 关键词。
 */
@Component
public class KeywordBlacklistDetector implements GuardrailDetector {

    // 常见 prompt injection 关键词模式
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("(?i)ignore\\s+(previous|prior|above|all)\\s+instructions"),
            Pattern.compile("(?i)disregard\\s+(previous|prior|above|all)"),
            Pattern.compile("(?i)you\\s+are\\s+(now|a|an)\\s+(dan|developer|admin)"),
            Pattern.compile("(?i)system\\s*prompt", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)reveal\\s+(your|the)\\s+(system|initial)\\s+prompt"),
            Pattern.compile("(?i)jailbreak"),
            Pattern.compile("(?i)act\\s+as\\s+(if|a)\\s+(you|different)"),
            Pattern.compile("(?i)override\\s+(safety|content|filter)"),
            Pattern.compile("(?i)忘记|忽略|无视.*(之前|上面|所有).*(指令|提示|规则)")
    );

    @Override
    public String name() { return "keyword_blacklist"; }

    @Override
    public String direction() { return "INPUT"; }

    @Override
    public GuardrailResult check(String content, String userId, String sessionId) {
        if (content == null || content.isBlank()) {
            return GuardrailResult.pass(name());
        }
        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(content).find()) {
                return GuardrailResult.block(name(), 0.9,
                        "检测到 prompt injection 关键词: " + p.pattern(), "PROMPT_INJECTION");
            }
        }
        return GuardrailResult.pass(name());
    }
}
