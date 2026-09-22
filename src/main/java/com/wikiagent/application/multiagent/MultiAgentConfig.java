package com.wikiagent.application.multiagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v6 §21.5 装配 9×6=54 个 {@link DomainSupervisor}，5 个 {@link IntentAgent} 共享。
 * <p>
 * <b>9 领域</b>（§7.4 行业垂直隔离）：
 * industry_solution / merchant_center / pms / service_provider / trunk_line /
 * customs / settlement / first_mile / trajectory
 * <p>
 * <b>6 子领域</b>（§7.4 知识类型细分）：
 * product_doc / operation_manual / faq / case_library / rule_config / api_doc
 * <p>
 * <b>5 身份</b>（运行时由 Perception.intent 体现，不参与 registry key）：
 * admin / business / product / technology / test
 * <p>
 * 装配策略：所有 54 个 (domain, subDomain) 共享同一组 5 个 IntentAgent（ singleton），
 * identity="any" 通配；运行时按用户实际身份精确匹配 TenantKey，未命中时 fallback 到 "any"。
 * <p>
 * 与 §21.5 骨架的差异：原方案是按 9×6×5=270 装配，但 5 身份通过 Perception.intent 路由（不参与 registry），
 * 实际只需 9×6=54 个 DomainSupervisor 实例（每个内含 5 IntentAgent 共享）。
 */
@Configuration
public class MultiAgentConfig {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentConfig.class);

    public static final List<String> DOMAINS = List.of(
            "industry_solution", "merchant_center", "pms",
            "service_provider", "trunk_line", "customs",
            "settlement", "first_mile", "trajectory");

    public static final List<String> SUB_DOMAINS = List.of(
            "product_doc", "operation_manual", "faq",
            "case_library", "rule_config", "api_doc");

    public static final List<String> IDENTITIES = List.of(
            "admin", "business", "product", "technology", "test");

    @Bean
    Map<TenantKey, DomainSupervisor> domainSupervisorRegistry(List<IntentAgent> agents) {
        if (agents.isEmpty()) {
            throw new IllegalStateException("未找到任何 IntentAgent Bean，请检查 @ComponentScan");
        }
        Map<TenantKey, DomainSupervisor> registry = new HashMap<>();
        for (String d : DOMAINS) {
            for (String sd : SUB_DOMAINS) {
                registry.put(TenantKey.anyIdentity(d, sd),
                        new DomainSupervisor(d, sd, agents));
            }
        }
        log.info("MultiAgentConfig 装配完成: {} 个 DomainSupervisor, {} 个 IntentAgent 共享",
                registry.size(), agents.size());
        return registry;
    }
}
