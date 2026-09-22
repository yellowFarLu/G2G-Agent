package com.wikiagent.application.multiagent;

import com.wikiagent.application.agent.pero.Perception;
import com.wikiagent.service.chat.SseSender;

/**
 * v6 §21.5 Intent 子 Agent 接口（5 类意图）。
 * <p>
 * 5 类意图（与 §7.4 / §21.5 配置一致）：
 * <ul>
 *   <li>{@code knowledge_qa} — 知识问答（真实，委托 §20 PERO 主循环）</li>
 *   <li>{@code ai_coding} — AI Coding（Mock，§7.4 模板未实施）</li>
 *   <li>{@code customer_intake} — 客户接入（Mock）</li>
 *   <li>{@code business_rule_config} — 业务规则配置（Mock）</li>
 *   <li>{@code order_query} — 订单查询（Mock）</li>
 * </ul>
 * 调用方式：{@link DomainSupervisor} 按 {@link Perception#intent()} 路由到对应 IntentAgent，
 * IntentAgent 内部产出最终答案并通过 {@link SseSender} 推送 SSE 事件（delta / done）。
 * <p>
 * v3-v5 实施时由 {@code PerceptionService.perceive()} 注入真实意图分类结果，
 * v6 默认走 {@link com.wikiagent.application.agent.pero.SimplePerception}（intent=knowledge_qa）。
 */
public interface IntentAgent {

    /** 5 类意图之一（与 {@code wikiagent.multi-agent.intent-agents} 配置一致）。 */
    String intent();

    /** 执行 Agent：按 Perception 上下文产出最终答案，通过 SseSender 推送事件。 */
    void invoke(Perception ctx, SseSender sse);
}
