package com.wikiagent.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 子项目 J（AC-J1）：DashScope API Key 配置健康检查。
 * <p>
 * 生产/预发环境缺失 {@code DASHSCOPE_API_KEY} 时<b>不阻止应用启动</b>
 * （健康检查/管理端口仍可用于排障），但 {@code /actuator/health} 中
 * {@code dashScopeKey} 指标为 DOWN，使编排平台的就绪探针能拦截"密钥缺失"的实例，
 * 避免带空密钥的 Pod 静默承接流量后全部走降级。
 * <p>
 * 仅在 staging/prod profile 注册：本地开发不配置 key 是常态，不应污染本地健康状态。
 */
@Component
@Profile({"staging", "prod"})
public class DashScopeKeyHealthIndicator implements HealthIndicator {

    static final String INDICATOR_NAME = "dashScopeKey";

    private final String apiKey;

    public DashScopeKeyHealthIndicator(
            @org.springframework.beans.factory.annotation.Value("${DASHSCOPE_API_KEY:}") String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public Health health() {
        if (apiKey == null || apiKey.isBlank()) {
            return Health.down()
                    .withDetail("configured", false)
                    .withDetail("reason", "DASHSCOPE_API_KEY 未配置：LLM 相关能力不可用，"
                            + "请勿将该实例加入就绪池（健康检查 DOWN，应用本身保持启动）")
                    .build();
        }
        // 绝不回显密钥内容，仅暴露是否配置与前缀指纹
        return Health.up()
                .withDetail("configured", true)
                .withDetail("prefix", apiKey.length() <= 3 ? "***" : apiKey.substring(0, 3) + "***")
                .build();
    }
}
