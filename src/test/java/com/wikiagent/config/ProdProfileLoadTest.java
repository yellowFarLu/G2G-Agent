package com.wikiagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-J1 prod profile 安全默认加载测试：
 * ddl-auto=validate、debug=false、fallback/mock/noop 显式关闭或受控、
 * actuator 仅 health/info/prometheus、凭据全部为无默认值环境变量占位、
 * 信任边界/PII 最小化/限流默认开启、SSE 与上传上限显式。
 */
class ProdProfileLoadTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=prod");

    @Test
    void prodProfile安全默认齐备() {
        runner.run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            var env = context.getEnvironment();

            // === 安全默认：DDL validate / debug 关 / Flyway 校验 ===
            assertThat(ProfilePropertySupport.raw(env, "spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            assertThat(ProfilePropertySupport.raw(env, "debug")).isEqualTo("false");
            assertThat(ProfilePropertySupport.raw(env, "spring.flyway.enabled")).isEqualTo("true");
            assertThat(ProfilePropertySupport.raw(env, "spring.flyway.validate-on-migrate")).isEqualTo("true");

            // === 凭据：全部无默认值环境变量占位，不存在明文默认密码 ===
            ProfilePropertySupport.assertRequiredPlaceholder(
                    ProfilePropertySupport.raw(env, "spring.datasource.url"), "MYSQL_URL");
            ProfilePropertySupport.assertRequiredPlaceholder(
                    ProfilePropertySupport.raw(env, "spring.datasource.username"), "MYSQL_USER");
            ProfilePropertySupport.assertRequiredPlaceholder(
                    ProfilePropertySupport.raw(env, "spring.datasource.password"), "MYSQL_PASSWORD");
            ProfilePropertySupport.assertRequiredPlaceholder(
                    ProfilePropertySupport.raw(env, "spring.data.redis.host"), "REDIS_HOST");
            ProfilePropertySupport.assertRequiredPlaceholder(
                    ProfilePropertySupport.raw(env, "spring.data.redis.password"), "REDIS_PASSWORD");
            ProfilePropertySupport.assertRequiredPlaceholder(
                    ProfilePropertySupport.raw(env, "wikiagent.milvus.uri"), "MILVUS_URI");
            ProfilePropertySupport.assertRequiredPlaceholder(
                    ProfilePropertySupport.raw(env, "wikiagent.milvus.username"), "MILVUS_USERNAME");
            // 不得回落到基础配置中的 ${MILVUS_PASSWORD:milvus} 内置默认
            assertThat(ProfilePropertySupport.raw(env, "wikiagent.milvus.password"))
                    .isEqualTo("${MILVUS_PASSWORD}")
                    .doesNotContain(":root", ":milvus");
            // DASHSCOPE_API_KEY 允许空默认（启动不失败），由健康检查 DOWN 暴露
            assertThat(ProfilePropertySupport.raw(env, "spring.ai.dashscope.api-key"))
                    .isEqualTo("${DASHSCOPE_API_KEY:}");

            // === fallback/mock/noop 显式关闭或受控 ===
            assertThat(ProfilePropertySupport.raw(env, "wikiagent.fallback.web-search-enabled"))
                    .isEqualTo("${WIKIAGENT_FALLBACK_WEB_SEARCH_ENABLED:false}");
            assertThat(ProfilePropertySupport.raw(env, "wikiagent.fallback.own-knowledge-enabled"))
                    .isEqualTo("${WIKIAGENT_FALLBACK_OWN_KNOWLEDGE_ENABLED:false}");
            assertThat(ProfilePropertySupport.raw(env, "wikiagent.routing.mock-intents-enabled")).isEqualTo("false");
            assertThat(ProfilePropertySupport.raw(env, "wikiagent.parse.provider"))
                    .isEqualTo("${WIKIAGENT_PARSE_PROVIDER:none}");
            // rerank 默认开启（需求4重排实装）；无 API Key 时 provider 不可用自动降级，可显式关闭
            assertThat(ProfilePropertySupport.raw(env, "wikiagent.rerank.enabled"))
                    .isEqualTo("${WIKIAGENT_RERANK_ENABLED:true}");

            // === actuator 仅 health/info/prometheus；错误响应不回显细节 ===
            assertThat(ProfilePropertySupport.raw(env, "management.endpoints.web.exposure.include"))
                    .isEqualTo("health,info,prometheus");
            assertThat(ProfilePropertySupport.raw(env, "server.error.include-stacktrace")).isEqualTo("never");
            assertThat(ProfilePropertySupport.raw(env, "server.error.include-message")).isEqualTo("never");

            // === SSE 与上传上限显式 ===
            assertThat(ProfilePropertySupport.raw(env, "spring.mvc.async.request-timeout")).isEqualTo("-1");
            assertThat(ProfilePropertySupport.raw(env, "spring.servlet.multipart.max-file-size"))
                    .isEqualTo("${MAX_FILE_SIZE:50MB}");
            assertThat(ProfilePropertySupport.raw(env, "spring.servlet.multipart.max-request-size"))
                    .isEqualTo("${MAX_REQUEST_SIZE:200MB}");

            // === 安全网关：信任边界默认开、PII 最小化默认开、限流契约默认开 ===
            assertThat(ProfilePropertySupport.raw(env, "wikiagent.security.trust-boundary.enabled"))
                    .isEqualTo("${WIKIAGENT_TRUST_BOUNDARY_ENABLED:true}");
            assertThat(ProfilePropertySupport.raw(env, "wikiagent.security.trust-boundary.secret"))
                    .isEqualTo("${WIKIAGENT_INTERNAL_SECRET:}");
            assertThat(ProfilePropertySupport.raw(env, "wikiagent.security.pii-minimization.enabled"))
                    .isEqualTo("${WIKIAGENT_PII_MINIMIZATION_ENABLED:true}");
            assertThat(ProfilePropertySupport.raw(env, "wikiagent.ratelimit.enabled"))
                    .isEqualTo("${WIKIAGENT_RATELIMIT_ENABLED:true}");
            assertThat(ProfilePropertySupport.raw(env, "wikiagent.ratelimit.requests-per-minute"))
                    .isEqualTo("${WIKIAGENT_RATELIMIT_PER_MINUTE:60}");

            // === 任务 MQ / Redis / Session 生产口径 ===
            assertThat(ProfilePropertySupport.raw(env, "wikiagent.task.mq")).isEqualTo("${TASK_MQ:rocketmq}");
            assertThat(ProfilePropertySupport.raw(env, "wikiagent.redis.enabled")).isEqualTo("true");
            assertThat(ProfilePropertySupport.raw(env, "spring.session.store-type")).isEqualTo("redis");
        });
    }
}
