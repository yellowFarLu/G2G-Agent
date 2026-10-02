package com.wikiagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-J1 dev profile 加载测试：验证本地 profile 与"不激活 profile"时的历史行为一致
 * （ddl-auto=update、Flyway 开启），且 mock/降级全开、信任边界默认关闭、SQL 可见。
 */
class DevProfileLoadTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=dev");

    @Test
    void devProfile保持本地现状且调试友好() {
        runner.run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            var env = context.getEnvironment();

            // 保持现状：JPA update + Flyway 开启（不校验 H2 方言差异）
            assertThat(ProfilePropertySupport.raw(env, "spring.jpa.hibernate.ddl-auto")).isEqualTo("update");
            assertThat(ProfilePropertySupport.raw(env, "spring.flyway.enabled")).isEqualTo("true");
            assertThat(ProfilePropertySupport.raw(env, "spring.flyway.validate-on-migrate")).isEqualTo("false");

            // debug 友好 / SQL 信息可开
            assertThat(ProfilePropertySupport.raw(env, "spring.jpa.show-sql")).isEqualTo("true");
            assertThat(ProfilePropertySupport.raw(env, "logging.level.com.wikiagent")).isEqualTo("DEBUG");

            // mock/降级全开
            assertThat(ProfilePropertySupport.raw(env, "wikiagent.fallback.web-search-enabled")).isEqualTo("true");
            assertThat(ProfilePropertySupport.raw(env, "wikiagent.fallback.own-knowledge-enabled")).isEqualTo("true");
            assertThat(ProfilePropertySupport.raw(env, "wikiagent.routing.mock-intents-enabled")).isEqualTo("true");

            // 共享密钥过滤器本地默认关闭
            assertThat(ProfilePropertySupport.raw(env, "wikiagent.security.trust-boundary.enabled"))
                    .isEqualTo("false");
            // PII 最小化默认不干扰本地调试
            assertThat(ProfilePropertySupport.raw(env, "wikiagent.security.pii-minimization.enabled"))
                    .isEqualTo("false");
        });
    }
}
