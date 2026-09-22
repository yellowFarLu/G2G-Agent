package com.wikiagent.infrastructure.gateway;

/**
 * v5 §19 安全网关检测结果（值对象）。
 *
 * @param passed             是否通过检测
 * @param detectorName       检测器名称
 * @param riskScore          风险分数 0.0-1.0
 * @param reason             拦截/告警原因
 * @param sanitizedContent   脱敏后内容（null 表示无需脱敏）
 * @param violationType      违规类型（PROMPT_INJECTION / JAILBREAK / PII_LEAK / TOXIC_CONTENT / SYSTEM_PROMPT_LEAK / PROTECTED_MATERIAL）
 */
public record GuardrailResult(
        boolean passed,
        String detectorName,
        double riskScore,
        String reason,
        String sanitizedContent,
        String violationType
) {
    public static GuardrailResult pass(String detectorName) {
        return new GuardrailResult(true, detectorName, 0.0, null, null, null);
    }

    public static GuardrailResult block(String detectorName, double riskScore,
                                        String reason, String violationType) {
        return new GuardrailResult(false, detectorName, riskScore, reason, null, violationType);
    }

    public static GuardrailResult sanitize(String detectorName, double riskScore,
                                            String reason, String sanitizedContent,
                                            String violationType) {
        return new GuardrailResult(false, detectorName, riskScore, reason, sanitizedContent, violationType);
    }
}
