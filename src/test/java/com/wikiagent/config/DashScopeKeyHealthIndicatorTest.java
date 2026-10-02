package com.wikiagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-J1：{@link DashScopeKeyHealthIndicator} 单测——
 * 缺 key 时 DOWN（不抛异常、不阻断启动语义），有 key 时 UP 且不回显密钥。
 */
class DashScopeKeyHealthIndicatorTest {

    @Test
    void 缺失apiKey时健康DOWN且不泄露() {
        Health health = new DashScopeKeyHealthIndicator("").health();
        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("configured", false);
        assertThat(String.valueOf(health.getDetails())).doesNotContain("sk-");
    }

    @Test
    void nullApiKey同样DOWN() {
        Health health = new DashScopeKeyHealthIndicator(null).health();
        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
    }

    @Test
    void 配置apiKey时UP且仅暴露前缀指纹() {
        Health health = new DashScopeKeyHealthIndicator("sk-abcdef123456").health();
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("configured", true);
        assertThat(String.valueOf(health.getDetails())).contains("sk-***").doesNotContain("abcdef123456");
    }
}
