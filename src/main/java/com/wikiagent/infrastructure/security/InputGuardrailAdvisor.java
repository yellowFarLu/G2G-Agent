package com.wikiagent.infrastructure.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * v1-v2 §10 输入护栏 - 检测用户输入中的提示注入与越狱攻击。
 * <p>
 * 检测模式：
 * <ul>
 *   <li>提示注入模式：DAN、ignore previous、system prompt 等关键词</li>
 *   <li>黑名单关键词：敏感词与危险指令</li>
 *   <li>越狱尝试：角色扮演绕过、指令覆盖</li>
 * </ul>
 * 检测命中时返回 blocked=true 的 {@link GuardrailResult}。
 * <p>
 * 开关：{@code wikiagent.security.input-guardrail-enabled=true}（默认开启）。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.security.input-guardrail-enabled",
        havingValue = "true", matchIfMissing = true)
public class InputGuardrailAdvisor {

    /** 提示注入模式正则列表（大小写不敏感）。 */
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("(?i)DAN\\s*mode"),
            Pattern.compile("(?i)do\\s*anything\\s*now"),
            Pattern.compile("(?i)ignore\\s+(previous|prior|above|all)\\s*(instructions?|prompts?|rules?)"),
            Pattern.compile("(?i)disregard\\s+(previous|prior|above|all)\\s*(instructions?|prompts?|rules?)"),
            Pattern.compile("(?i)system\\s*prompt"),
            Pattern.compile("(?i)reveal\\s+(your|the)\\s+(system\\s*)?prompt"),
            Pattern.compile("(?i)you\\s+are\\s+(now|a)\\s+(DAN|jailbreak|developer)"),
            Pattern.compile("(?i)act\\s+as\\s+(if\\s+)?(DAN|jailbreak|developer|root|admin)"),
            Pattern.compile("(?i)override\\s+(your|the)\\s+(instructions?|rules?|guidelines?)"),
            Pattern.compile("(?i)forget\\s+(your|all)\\s+(previous\\s+)?(instructions?|rules?|prompts?)")
    );

    /** 黑名单关键词（精确匹配，大小写不敏感）。 */
    private static final List<String> BLACKLIST = List.of(
            "<script", "javascript:", "eval(", "exec(", "system(",
            "rm -rf", "mkfs", "dd if=", "chmod 777",
            "DROP TABLE", "DELETE FROM", "INSERT INTO",
            "<?php", "<%=", "execmap"
    );

    /**
     * 检查用户输入是否包含提示注入或越狱攻击。
     *
     * @param userInput 用户输入文本
     * @return 检查结果（blocked=true 表示应拦截）
     */
    public GuardrailResult check(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return GuardrailResult.allowed(null);
        }

        // 检查提示注入模式
        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(userInput).find()) {
                return GuardrailResult.blocked("prompt_injection",
                        "检测到提示注入模式：" + p.pattern());
            }
        }

        // 检查黑名单关键词
        String lowerInput = userInput.toLowerCase();
        for (String kw : BLACKLIST) {
            if (lowerInput.contains(kw.toLowerCase())) {
                return GuardrailResult.blocked("blacklist_keyword",
                        "检测到黑名单关键词：" + kw);
            }
        }

        return GuardrailResult.allowed(null);
    }

    /**
     * 护栏检查结果。
     *
     * @param blocked         是否拦截
     * @param reason          拦截原因（blocked=true 时非空）
     * @param sanitizedInput  清洗后的输入（当前实现返回 null）
     */
    public record GuardrailResult(boolean blocked, String reason, String sanitizedInput) {

        /** 构造放行结果。 */
        public static GuardrailResult allowed(String sanitizedInput) {
            return new GuardrailResult(false, null, sanitizedInput);
        }

        /** 构造拦截结果。 */
        public static GuardrailResult blocked(String reason, String detail) {
            return new GuardrailResult(true, reason + (detail != null ? " | " + detail : ""), null);
        }
    }
}
