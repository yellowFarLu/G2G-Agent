package com.wikiagent.application.agent.pero;

import com.wikiagent.domain.agent.Reflection;

/**
 * v6 §20 Perception 端口的默认最小实现。
 * <p>
 * v3-v5 实施时由 {@code PerceptionService.perceive()} 构造具体实现
 * （含用户档案 / 短期记忆 / 意图分类），本类仅作 v6 主循环自洽的默认实现。
 * <p>
 * intent 取值：knowledge_qa（默认）/ ai_coding / customer_intake /
 * business_rule_config / order_query（§7.4 Mock 4 类）。
 */
public record SimplePerception(String userId, String sessionId,
                               String userInput, String intent) implements Perception {

    public SimplePerception(String userId, String sessionId, String userInput) {
        this(userId, sessionId, userInput, "knowledge_qa");
    }

    @Override
    public Perception with(Reflection reflection) {
        // 携带 hint（reworkHint）复制为新 Perception；intent 不变，便于 episodic recall
        String newHint = reflection == null ? null : reflection.reworkHint();
        String combined = (userInput == null ? "" : userInput)
                + (newHint == null ? "" : "\n[反思 hint] " + newHint);
        return new SimplePerception(userId, sessionId, combined, intent);
    }
}
