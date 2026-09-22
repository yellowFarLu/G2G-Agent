package com.wikiagent.infrastructure.security;

import com.wikiagent.infrastructure.security.InputGuardrailAdvisor.GuardrailResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * v1-v2 §10 输出护栏 - 检测 LLM 输出中的系统提示泄露与 PII 暴露。
 * <p>
 * 检测模式：
 * <ul>
 *   <li>系统提示泄露：输出中包含 system prompt 内容标记</li>
 *   <li>PII 暴露：手机号、身份证号、银行卡号等敏感信息</li>
 *   <li>毒性内容：敏感词与有害内容标记</li>
 * </ul>
 * 检测命中时返回 blocked=true 的 {@link GuardrailResult}。
 * <p>
 * 开关：{@code wikiagent.security.output-guardrail-enabled=true}（默认开启）。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.security.output-guardrail-enabled",
        havingValue = "true", matchIfMissing = true)
public class OutputGuardrailAdvisor {

    /** 系统提示泄露模式正则（大小写不敏感）。 */
    private static final List<Pattern> SYSTEM_LEAK_PATTERNS = List.of(
            Pattern.compile("(?i)system\\s*prompt\\s*[:：]"),
            Pattern.compile("(?i)my\\s+(instructions?|system\\s*prompt)\\s+(is|are)\\s*[:：]"),
            Pattern.compile("(?i)you\\s+are\\s+a\\s+.*assistant.*\\n.*follow")
    );

    /** PII 模式正则。 */
    private static final List<Pattern> PII_PATTERNS = List.of(
            Pattern.compile("\\b1[3-9]\\d{9}\\b"),                          // 中国手机号
            Pattern.compile("\\b\\d{17}[0-9Xx]\\b"),                         // 身份证号（18 位）
            Pattern.compile("\\b\\d{16,19}\\b"),                             // 银行卡号
            Pattern.compile("\\b\\d{3}-\\d{8}-\\d{4,5}\\b")                  // 座机格式
    );

    /** 毒性内容关键词（大小写不敏感）。 */
    private static final List<String> TOXIC_KEYWORDS = List.of(
            "涉政", "涉黄", "暴恐", "违法违规", "毒品", "枪支", "爆炸物"
    );

    /**
     * 检查 LLM 输出是否包含系统提示泄露或 PII 暴露。
     *
     * @param llmOutput LLM 输出文本
     * @return 检查结果（blocked=true 表示应拦截）
     */
    public GuardrailResult check(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) {
            return GuardrailResult.allowed(null);
        }

        // 检查系统提示泄露
        for (Pattern p : SYSTEM_LEAK_PATTERNS) {
            if (p.matcher(llmOutput).find()) {
                return GuardrailResult.blocked("system_prompt_leak",
                        "检测到系统提示泄露：" + p.pattern());
            }
        }

        // 检查 PII 暴露
        for (Pattern p : PII_PATTERNS) {
            if (p.matcher(llmOutput).find()) {
                return GuardrailResult.blocked("pii_exposure",
                        "检测到 PII 暴露：" + p.pattern());
            }
        }

        // 检查毒性内容
        for (String kw : TOXIC_KEYWORDS) {
            if (llmOutput.contains(kw)) {
                return GuardrailResult.blocked("toxic_content",
                        "检测到毒性内容关键词：" + kw);
            }
        }

        return GuardrailResult.allowed(null);
    }
}
