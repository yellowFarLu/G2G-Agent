package com.wikiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 子项目 J（AC-J4）：网关 ↔ 应用信任边界配置。
 * <p>
 * 前缀 {@code wikiagent.security.trust-boundary}：
 * <ul>
 *   <li>{@code enabled}（默认 <b>false</b>，仅 prod/staging 由部署变量置 true）：
 *       是否启用 {@code TrustedHeaderFilter} 共享密钥校验；</li>
 *   <li>{@code secret}：共享密钥，只能由环境变量 {@code WIKIAGENT_INTERNAL_SECRET} 注入，
 *       代码与配置文件中都不提供硬编码默认值；启用时为空 → 启动失败（fail-fast）；</li>
 *   <li>{@code identity-headers}：受保护的身份头白名单，默认
 *       {@code X-User-Id}、{@code X-Business-Identity}。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "wikiagent.security.trust-boundary")
public class TrustBoundaryProperties {

    private boolean enabled = false;

    /** 共享密钥；无硬编码默认，必须来自部署环境变量。 */
    private String secret;

    private List<String> identityHeaders =
            new ArrayList<>(List.of("X-User-Id", "X-Business-Identity"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public List<String> getIdentityHeaders() {
        return identityHeaders;
    }

    public void setIdentityHeaders(List<String> identityHeaders) {
        this.identityHeaders = identityHeaders == null ? new ArrayList<>() : identityHeaders;
    }
}
