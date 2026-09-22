package com.wikiagent.application.multiagent;

import java.util.Objects;

/**
 * v6 §21.5 多租户垂直隔离 Key（9 领域 × 6 子领域 × 5 身份 = 270 路由组合）。
 * <p>
 * 9×6=54 个 (domain, subDomain) 组合装配 {@link DomainSupervisor}，5 个 IntentAgent 共享；
 * identity 字段用于 §13.8 验收 #25 路由断言（admin / business / product / technology / test）。
 * <p>
 * 用于 {@link MultiAgentOrchestrator} 的 registry Map key，必须可哈希（record 自动生成）。
 * identity="any" 表示通配，registry 装配时统一用 anyIdentity；运行时按用户实际身份精确匹配，
 * 未命中时 fallback 到 "any"。
 */
public record TenantKey(String domain, String subDomain, String identity) {

    public TenantKey {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(subDomain, "subDomain");
        Objects.requireNonNull(identity, "identity");
    }

    /** registry fallback 用：identity="any" 通配。 */
    public static TenantKey anyIdentity(String domain, String subDomain) {
        return new TenantKey(domain, subDomain, "any");
    }
}
