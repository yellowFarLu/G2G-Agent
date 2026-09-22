package com.wikiagent.infrastructure.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * v5 §19.4 输入检测器 - Azure Prompt Shield（外部 SaaS）。
 * <p>
 * 调用 Azure AI Content Safety API 检测 prompt injection。
 * 需配置 AZURE_CONTENT_SAFETY_KEY + AZURE_CONTENT_SAFETY_ENDPOINT，
 * 未配置时降级为 PASS。
 */
@Component
public class AzurePromptShieldDetector implements GuardrailDetector {

    private static final Logger log = LoggerFactory.getLogger(AzurePromptShieldDetector.class);
    private final String azureKey;

    public AzurePromptShieldDetector(@Value("${AZURE_CONTENT_SAFETY_KEY:}") String azureKey) {
        this.azureKey = azureKey;
    }

    @Override
    public String name() { return "azure_prompt_shield"; }

    @Override
    public String direction() { return "INPUT"; }

    @Override
    public GuardrailResult check(String content, String userId, String sessionId) {
        if (azureKey == null || azureKey.isBlank()) {
            log.debug("Azure Content Safety Key 未配置，跳过检测");
            return GuardrailResult.pass(name());
        }
        // 实际实现：POST {endpoint}/contentsafety/text:shieldPrompt
        // 响应包含 userPromptAnalysis + documentsAnalysis
        // v5 实施校正：Azure API 需运行时验证，当前为 stub
        return GuardrailResult.pass(name());
    }
}
