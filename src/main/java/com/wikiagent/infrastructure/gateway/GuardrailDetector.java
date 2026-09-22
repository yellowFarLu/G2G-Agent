package com.wikiagent.infrastructure.gateway;

/**
 * v5 §19 安全网关检测器接口。
 * <p>
 * 每个检测器检查输入或输出内容，返回检测结果。
 */
public interface GuardrailDetector {

    /** 检测器名称（如 keyword_blacklist, llm_judge, moderation 等）。 */
    String name();

    /** 检测方向：INPUT 或 OUTPUT。 */
    String direction();

    /**
     * 检测内容是否安全。
     *
     * @param content   待检测内容（用户输入或 LLM 输出）
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @return 检测结果
     */
    GuardrailResult check(String content, String userId, String sessionId);
}
