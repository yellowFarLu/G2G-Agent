package com.wikiagent.application.agent.pero;

import com.wikiagent.domain.agent.PlanStep;

import java.util.List;

/**
 * v6 §20 PERO 节点内 ReAct 工具注册与白名单端口。
 * <p>
 * §2.6 既有 ToolRegistry 设计：注册所有可用工具，按 PlanStep.stepType() 返回白名单。
 * §20.6 配置 {@code wikiagent.pero.react.tool-whitelist-by-step-type} 定义按节点类型的工具白名单：
 * <ul>
 *   <li>search_kb: [search_knowledge_base]</li>
 *   <li>search_history: [search_history]</li>
 *   <li>update_profile: [update_user_profile]</li>
 *   <li>tool: [search_knowledge_base, search_history, update_user_profile, read_handover, list_abandoned_paths]</li>
 *   <li>generate: []（无需工具，LLM 直答）</li>
 * </ul>
 * 本接口作为端口契约（v3-v5 实施时具体化为 {@code ToolRegistry + ToolPolicyInterceptor}）。
 */
public interface ToolRegistry {

    /** 返回该 step 允许调用的工具名白名单。 */
    List<String> allowedTools(PlanStep step);
}
