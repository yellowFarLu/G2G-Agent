package com.wikiagent.application.multiagent;

import com.wikiagent.application.agent.pero.Perception;
import com.wikiagent.application.agent.pero.SimplePerception;
import com.wikiagent.service.chat.SseSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * v6 §21.5 多 Agent 编排入口（替代 §2 AgentOrchestrator 占位）。
 * <p>
 * 启动期由 {@link MultiAgentConfig} 装配 9×6=54 个 {@link DomainSupervisor}，
 * 运行时按 {@link TenantKey} 路由：精确匹配 → any identity fallback → 抛异常。
 * DomainSupervisor 再按 {@link Perception#intent()} 路由到对应 {@link IntentAgent}。
 * <p>
 * <b>路由两层结构</b>：
 * <ol>
 *   <li>第一层（租户隔离）：{@code TenantKey} → {@link DomainSupervisor}</li>
 *   <li>第二层（意图路由）：{@code Perception.intent()} → {@link IntentAgent}</li>
 * </ol>
 * <p>
 * 开关：{@code wikiagent.multi-agent.enabled=false} 时由 ChatService 回退 §2 AgentRagService。
 * <p>
 * 与 §2 AgentRagService 的区别：§2 是单 Agent 顺序 RAG 管道（rewrite → retrieve → generate）；
 * v6 是 Multi-Agent 编排，按 9×6×5 垂直隔离 + 5 类意图分类路由到不同 Agent 执行。
 */
@Component
public class MultiAgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentOrchestrator.class);

    private final Map<TenantKey, DomainSupervisor> registry;
    private final boolean enabled;

    public MultiAgentOrchestrator(Map<TenantKey, DomainSupervisor> registry,
                                  @Value("${wikiagent.multi-agent.enabled:true}") boolean enabled) {
        this.registry = registry;
        this.enabled = enabled;
        log.info("MultiAgentOrchestrator 初始化: enabled={}, 注册 {} 个 DomainSupervisor",
                enabled, registry.size());
    }

    public boolean enabled() {
        return enabled;
    }

    /** 注册表大小（用于 §13.8 #25 验收断言 9×6=54）。 */
    public int registrySize() {
        return registry.size();
    }

    /** 按 TenantKey 查询 DomainSupervisor（用于 §13.8 #25 路由断言测试）。 */
    public DomainSupervisor supervisorOf(TenantKey key) {
        DomainSupervisor sup = registry.get(key);
        if (sup == null) {
            sup = registry.get(TenantKey.anyIdentity(key.domain(), key.subDomain()));
        }
        return sup;
    }

    /**
     * 主入口：按 TenantKey 路由到 DomainSupervisor，构造 Perception 后调用 Supervisor.run。
     * <p>
     * 异常向上抛出，由 ChatService 统一转 SSE error 事件。
     */
    public void run(TenantKey key, String userId, String sessionId,
                    String userInput, SseSender sse) {
        if (!enabled) {
            throw new IllegalStateException("Multi-Agent 已禁用，请走 §2 AgentRagService");
        }
        DomainSupervisor sup = supervisorOf(key);
        if (sup == null) {
            throw new IllegalStateException("无 DomainSupervisor 可用: " + key);
        }
        Perception ctx = new SimplePerception(userId, sessionId, userInput);
        sup.run(ctx, sse);
    }
}
