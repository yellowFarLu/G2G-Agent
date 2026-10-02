package com.wikiagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-J1 staging profile 加载测试：类生产口径——validate、Flyway 校验开启、
 * 降级开关显式（默认关）、日志 INFO、actuator 收紧、无内置默认密码、信任边界默认演练开启。
 */
class StagingProfileLoadTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=staging");

    @Test
    void stagingProfile类生产配置与显式开关() {
        runner.run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            var env = context.getEnvironment();

            // 类生产：JPA validate + Flyway 校验
            assertThat(ProfilePropertySupport.raw(env, "spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            assertThat(ProfilePropertySupport.raw(env, "spring.flyway.enabled")).isEqualTo("true");
            assertThat(ProfilePropertySupport.raw(env, "spring.flyway.validate-on-migrate")).isEqualTo("true");

            // 数据源/Redis 全部环境变量占位、无默认密码
            ProfilePropertySupport.assertRequiredPlaceholder(
                    ProfilePropertySupport.raw(env, "spring.datasource.password"), "MYSQL_PASSWORD");
            ProfilePropertySupport.assertRequiredPlaceholder(
                    ProfilePropertySupport.raw(env, "spring.datasource.url"), "MYSQL_URL");
            ProfilePropertySupport.assertRequiredPlaceholder(
                    ProfilePropertySupport.raw(env, "spring.data.redis.password"), "REDIS_PASSWORD");
            ProfilePropertySupport.assertRequiredPlaceholder(
                    ProfilePropertySupport.raw(env, "wikiagent.milvus.password"), "MILVUS_PASSWORD");

            // 日志 INFO；actuator 相对 prod 多 metrics 用于排障，但不暴露 env/beans
            assertThat(ProfilePropertySupport.raw(env, "logging.level.com.wikiagent")).isEqualTo("INFO");
            String exposure = ProfilePropertySupport.raw(env, "management.endpoints.web.exposure.include");
            assertThat(exposure).contains("health", "info", "prometheus", "metrics");
            assertThat(exposure).doesNotContain("env", "beans", "heapdump", "threaddump");

            // 降级开关显式（默认关，可由部署变量打开）
            assertThat(ProfilePropertySupport.raw(env, "wikiagent.fallback.web-search-enabled"))
                    .isEqualTo("${WIKIAGENT_FALLBACK_WEB_SEARCH_ENABLED:false}");
            assertThat(ProfilePropertySupport.raw(env, "wikiagent.fallback.own-knowledge-enabled"))
                    .isEqualTo("${WIKIAGENT_FALLBACK_OWN_KNOWLEDGE_ENABLED:false}");
            assertThat(ProfilePropertySupport.raw(env, "wikiagent.routing.mock-intents-enabled")).isEqualTo("false");

            // 信任边界默认演练开启；密钥仅有空默认（启用时由过滤器 fail-fast，等价于无默认值）
            assertThat(ProfilePropertySupport.raw(env, "wikiagent.security.trust-boundary.enabled"))
                    .isEqualTo("${WIKIAGENT_TRUST_BOUNDARY_ENABLED:true}");
            assertThat(ProfilePropertySupport.raw(env, "wikiagent.security.trust-boundary.secret"))
                    .isEqualTo("${WIKIAGENT_INTERNAL_SECRET:}");
        });
    }
}
