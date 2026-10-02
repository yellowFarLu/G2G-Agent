package com.wikiagent.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 子项目 J（AC-J3）：PII 最小化外发配置。
 * <p>
 * 前缀 {@code wikiagent.security.pii-minimization}：
 * <ul>
 *   <li>{@code enabled}（默认 false）：是否用 {@link PiiMinimizingChatModelDecorator}
 *       包装全部 ChatModel Bean。本地默认关闭不干扰调试；prod profile 默认开启。</li>
 *   <li>{@code policies.<category>=mask|block}：类别键 phone/id-card/bank-card/email，
 *       缺省按 mask 处理。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "wikiagent.security.pii-minimization")
public class PiiMinimizationProperties {

    private boolean enabled = false;

    /** 保持插入顺序，便于日志/文档稳定输出。 */
    private Map<String, String> policies = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, String> getPolicies() {
        return policies;
    }

    public void setPolicies(Map<String, String> policies) {
        this.policies = policies == null ? new LinkedHashMap<>() : policies;
    }
}
