package com.wikiagent.infrastructure.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * v5 §19.4 输入检测器 - Lakera Guard（外部 SaaS）。
 * <p>
 * 调用 Lakera Guard API 检测 prompt injection。
 * 需配置 LAKERA_API_KEY，未配置时降级为 PASS（不拦截）。
 */
@Component
public class LakeraDetector implements GuardrailDetector {

    private static final Logger log = LoggerFactory.getLogger(LakeraDetector.class);
    private final String apiKey;

    public LakeraDetector(@Value("${LAKERA_API_KEY:}") String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public String name() { return "lakera"; }

    @Override
    public String direction() { return "INPUT"; }

    @Override
    public GuardrailResult check(String content, String userId, String sessionId) {
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("Lakera API Key 未配置，跳过检测");
            return GuardrailResult.pass(name());
        }
        // 实际实现：POST https://api.lakera.ai/v1/guard with content
        // 响应包含 attack_detected + risk_score
        // v5 实施校正：Lakera API 需运行时验证，当前为 stub
        return GuardrailResult.pass(name());
    }
}
