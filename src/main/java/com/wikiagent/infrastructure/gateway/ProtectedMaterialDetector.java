package com.wikiagent.infrastructure.gateway;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * v5 §19.5 输出检测器 - 受保护材料检测。
 * <p>
 * 检测 LLM 输出中是否包含受版权保护的文本片段。
 */
@Component
public class ProtectedMaterialDetector implements GuardrailDetector {

    // 常见受保护文本特征（生产环境应接入专业服务）
    private static final List<Pattern> PROTECTED_PATTERNS = List.of(
            // 版权声明
            Pattern.compile("(?i)copyright\\s+\\(c\\)\\s+\\d{4}"),
            Pattern.compile("(?i)all\\s+rights\\s+reserved"),
            // 许可证文本
            Pattern.compile("(?i)licensed\\s+under\\s+(MIT|Apache|GPL)"),
            // 水印标记
            Pattern.compile("(?i)DO\\s+NOT\\s+DISTRIBUTE"),
            Pattern.compile("(?i)CONFIDENTIAL")
    );

    @Override
    public String name() { return "protected_material"; }

    @Override
    public String direction() { return "OUTPUT"; }

    @Override
    public GuardrailResult check(String content, String userId, String sessionId) {
        if (content == null || content.isBlank()) {
            return GuardrailResult.pass(name());
        }
        for (Pattern p : PROTECTED_PATTERNS) {
            if (p.matcher(content).find()) {
                return GuardrailResult.block(name(), 0.7,
                        "检测到受保护材料: " + p.pattern(), "PROTECTED_MATERIAL");
            }
        }
        return GuardrailResult.pass(name());
    }
}
