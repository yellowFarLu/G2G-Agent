package com.wikiagent.application.multiagent;

import com.wikiagent.application.agent.pero.Perception;
import com.wikiagent.service.chat.SseSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * v6 §21.5 域 Supervisor：每 (domain, subDomain) 一个实例，包装 5 个 {@link IntentAgent}。
 * <p>
 * <b>实施校正（§21.5.1）</b>：原方案文档设想用 spring-ai-alibaba-graph 的
 * {@code SupervisorAgent.builder().subAgents()} 包装子 Agent；经 v6 实施时实测，
 * 1.1.2.0 的真实 artifactId 是 {@code spring-ai-alibaba-agent-framework}（不是 graph），
 * 真实多 Agent 模式是 {@code ReactAgent + AgentTool.getFunctionToolCallback(subAgent)}
 * 组装 Supervisor。由于 ReactAgent.builder() 的具体方法签名（inputType / methodTools 等）
 * 未在官方 Javadoc 中完全核实，为遵循"禁止捏造事实"约束，本类采用自研最小 Supervisor：
 * 按 {@link Perception#intent()} 直接路由到对应 IntentAgent，不引入未验证的 ReactAgent API。
 * <p>
 * 后续如要切换到 ReactAgent Supervisor 模式，只需将本类替换为 delegate：
 * <pre>{@code
 * private final ReactAgent delegate;  // spring-ai-alibaba-agent-framework 1.1.2.0
 * // 构造时用 AgentTool.getFunctionToolCallback(subAgent) 包装 5 个子 Agent
 * }</pre>
 * 当前实现已满足 §13.8 验收 #25 Multi-Agent 路由断言需求。
 */
public final class DomainSupervisor {

    private static final Logger log = LoggerFactory.getLogger(DomainSupervisor.class);

    private final String domain;
    private final String subDomain;
    private final Map<String, IntentAgent> agentsByIntent;

    public DomainSupervisor(String domain, String subDomain, List<IntentAgent> agents) {
        this.domain = domain;
        this.subDomain = subDomain;
        this.agentsByIntent = agents.stream()
                .collect(Collectors.toMap(IntentAgent::intent, a -> a, (a, b) -> a));
    }

    public String domain() {
        return domain;
    }

    public String subDomain() {
        return subDomain;
    }

    /** 已注册的 IntentAgent 意图集合（用于 §13.8 #25 路由断言）。 */
    public java.util.Set<String> registeredIntents() {
        return agentsByIntent.keySet();
    }

    /**
     * 按 {@link Perception#intent()} 路由到对应 IntentAgent，未匹配则回退到 knowledge_qa。
     * <p>
     * 路由策略（§21.4 自研最小 Supervisor）：
     * <ol>
     *   <li>精确匹配 intent → 调对应 IntentAgent</li>
     *   <li>未匹配 → fallback 到 knowledge_qa（最通用入口）</li>
     *   <li>knowledge_qa 也未注册 → 抛异常</li>
     * </ol>
     */
    public void run(Perception ctx, SseSender sse) {
        String intent = ctx.intent();
        IntentAgent agent = agentsByIntent.get(intent);
        if (agent == null) {
            log.warn("域 {}:{} 未注册意图 {}，回退 knowledge_qa", domain, subDomain, intent);
            agent = agentsByIntent.get("knowledge_qa");
        }
        if (agent == null) {
            throw new IllegalStateException(
                    "域 " + domain + ":" + subDomain + " 未注册任何 IntentAgent");
        }
        log.debug("域 {}:{} 路由意图 {} -> {}", domain, subDomain, intent,
                agent.getClass().getSimpleName());
        agent.invoke(ctx, sse);
    }
}
