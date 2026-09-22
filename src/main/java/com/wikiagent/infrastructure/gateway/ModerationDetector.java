package com.wikiagent.infrastructure.gateway;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * v5 §19.5 输出检测器 - 内容审核。
 * <p>
 * 检测 LLM 输出中是否包含涉政、涉黄、暴恐、违法违规内容。
 */
@Component
public class ModerationDetector implements GuardrailDetector {

    // 违规关键词（实际生产应接入 OpenAI Moderation API 或专业审核服务）
    private static final List<String> TOXIC_KEYWORDS = List.of(
            // 暴恐
            "炸弹", "爆炸物", "制造武器", "恐怖袭击",
            // 违法
            "毒品交易", "洗钱", "诈骗教程",
            // 其他敏感词（生产环境应更全面）
            "自杀方法", "自残指导"
    );

    @Override
    public String name() { return "moderation"; }

    @Override
    public String direction() { return "OUTPUT"; }

    @Override
    public GuardrailResult check(String content, String userId, String sessionId) {
        if (content == null || content.isBlank()) {
            return GuardrailResult.pass(name());
        }
        for (String keyword : TOXIC_KEYWORDS) {
            if (content.contains(keyword)) {
                return GuardrailResult.block(name(), 0.95,
                        "检测到违规内容: " + keyword, "TOXIC_CONTENT");
            }
        }
        return GuardrailResult.pass(name());
    }
}
