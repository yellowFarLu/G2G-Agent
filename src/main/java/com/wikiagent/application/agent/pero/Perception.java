package com.wikiagent.application.agent.pero;

import com.wikiagent.domain.agent.Reflection;

/**
 * v6 §20 PERO 主循环的感知上下文端口。
 * <p>
 * Perceive 阶段从 (userId, sessionId, userInput) 中提取上下文：
 * <ul>
 *   <li>用户档案（§5）：business_identity / assigned_domains / overrides</li>
 *   <li>短期记忆（§3）：最近 20 轮对话历史</li>
 *   <li>意图分类（§7 LLM 路由）：knowledge_qa / ai_coding / customer_intake /
 *       business_rule_config / order_query</li>
 * </ul>
 * <p>
 * 本接口作为端口契约（v3-v5 实施时具体化），v6 主循环只依赖以下最小方法：
 * <ul>
 *   <li>{@link #userId()} / {@link #sessionId()} / {@link #userInput()} — 上下文标识</li>
 *   <li>{@link #intent()} — 5 类意图之一，用于 episodic memory recall</li>
 *   <li>{@link #with(Reflection)} — 反思后携带 hint 复制为新 Perception（不可变语义）</li>
 * </ul>
 * v3-v5 实施时由 {@code PerceptionService.perceive()} 构造具体实现。
 */
public interface Perception {

    String userId();

    String sessionId();

    String userInput();

    String intent();

    /** 反思后携带 hint 复制为新 Perception，传入下一轮 ReAct。 */
    Perception with(Reflection reflection);
}
