package com.wikiagent.infrastructure.gateway;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * v5 §19.5 输出检测器 - 系统提示词泄露检测。
 * <p>
 * 检测 LLM 输出中是否泄露了系统提示词内容。
 */
@Component
public class SystemPromptLeakDetector implements GuardrailDetector {

    // 系统提示词特征模式
    private static final List<Pattern> LEAK_PATTERNS = List.of(
            // 系统提示词常见前缀
            Pattern.compile("(?i)you\\s+are\\s+a\\s+(helpful|knowledge|wiki)\\s+assistant"),
            Pattern.compile("(?i)your\\s+(task|role|mission)\\s+is\\s+to"),
            Pattern.compile("(?i)system\\s*:\\s*you\\s+are"),
            // 内部指令格式泄露
            Pattern.compile("(?i)<system>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)\\[INST\\]", Pattern.CASE_INSENSITIVE),
            // 特定提示词片段
            Pattern.compile("(?i)wikiagent.*prompt"),
            Pattern.compile("(?i)guardrail.*instruction"),
            Pattern.compile("(?i)rule\\s+of\\s+two")
    );

    @Override
    public String name() { return "system_prompt_leak"; }

    @Override
    public String direction() { return "OUTPUT"; }

    @Override
    public GuardrailResult check(String content, String userId, String sessionId) {
        if (content == null || content.isBlank()) {
            return GuardrailResult.pass(name());
        }
        for (Pattern p : LEAK_PATTERNS) {
            if (p.matcher(content).find()) {
                return GuardrailResult.block(name(), 0.85,
                        "检测到系统提示词泄露: " + p.pattern(), "SYSTEM_PROMPT_LEAK");
            }
        }
        return GuardrailResult.pass(name());
    }
}
