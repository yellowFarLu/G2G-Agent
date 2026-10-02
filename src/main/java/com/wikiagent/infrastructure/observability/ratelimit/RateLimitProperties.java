package com.wikiagent.infrastructure.observability.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 子项目 I（AC-I3）限流配置 {@code wikiagent.ratelimit}（默认值安全，无需改 application.yml）。
 *
 * <pre>
 * # 环境变量/yaml 覆盖样例：
 * wikiagent:
 *   ratelimit:
 *     enabled: true                  # 总开关，缺省 true（WIKIAGENT_RATELIMIT_ENABLED=false 关闭）
 *     requests-per-minute: 60        # 每 租户+用户 令牌桶容量与每分钟补充速率
 *     backpressure-retry-after-sec: 5  # 队列背压 429 的 Retry-After
 * </pre>
 * <p>
 * 单机实现必须；Redis 分布式令牌桶为后续可选项（见 {@link RateLimitFilter} javadoc）。
 */
@ConfigurationProperties(prefix = "wikiagent.ratelimit")
public class RateLimitProperties {

    /** 限流总开关（与 @ConditionalOnProperty 同名，缺省 true）。 */
    private boolean enabled = true;

    /** 每用户（+租户）每分钟许可请求数：同时是桶容量与线性补充速率。 */
    private int requestsPerMinute = 60;

    /** 队列背压拒绝时的 Retry-After（秒）。 */
    private int backpressureRetryAfterSec = 5;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getRequestsPerMinute() {
        return requestsPerMinute;
    }

    public void setRequestsPerMinute(int requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
    }

    public int getBackpressureRetryAfterSec() {
        return backpressureRetryAfterSec;
    }

    public void setBackpressureRetryAfterSec(int backpressureRetryAfterSec) {
        this.backpressureRetryAfterSec = backpressureRetryAfterSec;
    }
}
