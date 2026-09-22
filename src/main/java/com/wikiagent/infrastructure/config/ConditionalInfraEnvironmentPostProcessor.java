package com.wikiagent.infrastructure.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v1-v5/v6 实施校正：外部中间件条件装配。
 * <p>
 * 约束："应用在变更前可启动成功，变更后仍应可启动"——开发机不强制安装 Redis。
 * Redisson Spring Boot Starter 的 {@code RedissonAutoConfigurationV2} 在 Bean 实例化阶段
 * 就会连接 Redis（实测：无 Redis 时抛 {@code RedisConnectionException: Unable to connect
 * to Redis server: localhost/127.0.0.1:6379}，整个 ApplicationContext 启动失败），
 * 无法用 {@code @ConditionalOnProperty} 在业务 Bean 层规避，必须在自动装配层排除。
 * <p>
 * 本后处理器在配置文件加载之后执行（{@code ConfigDataEnvironmentPostProcessor}
 * 优先级更高），读取 {@code wikiagent.redis.enabled}：
 * <ul>
 *   <li>{@code false}（开发默认）：向环境追加 {@code spring.autoconfigure.exclude}，
 *       排除 Redisson / Redis 自动装配；业务侧由 @ConditionalOnProperty 切换到本地降级实现</li>
 *   <li>{@code true}（docker-compose / 生产）：不干预，自动装配正常连接 Redis</li>
 * </ul>
 * 通过 {@code META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports}
 * 注册，对 {@code @SpringBootTest} 测试上下文同样生效。
 */
public class ConditionalInfraEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String PROPERTY_SOURCE_NAME = "wikiagentInfraExcludes";

    /** Redis 关闭时需要排除的自动配置类（按 classpath 实际存在过滤，避免类缺失报错）。 */
    private static final String[] REDIS_AUTO_CONFIGURATIONS = {
            // 仅 V2 是 redisson-spring-boot-starter 注册的自动配置类；
            // 注意基类 RedissonAutoConfiguration 虽在 classpath 但未注册为自动配置，
            // 列入 exclude 会被 AutoConfigurationImportSelector 判为非法（实测）。
            "org.redisson.spring.starter.RedissonAutoConfigurationV2",
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
    };

    /**
     * DashScope API Key 缺失时必须排除的全部自动配置。
     * 实测 1.1.2.0 中所有 DashScope 自动装配在 Bean 实例化期均硬校验 Key
     * （Alibaba BOM 默认开启 spring.ai.model.* ；Chat 装配同样在被实际依赖时失败）。
     * Key 为空时全部排除：ChatModel 由本工程 DashScopeMultiModelFactory 提供
     * （NoOpChatModel 占位），EmbeddingModel 由 DashScopeFallbackConfig 提供。
     */
    private static final String[] DASHSCOPE_KEY_REQUIRED_AUTOCONFIGS = {
            "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatAutoConfiguration",
            "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeEmbeddingAutoConfiguration",
            "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAgentAutoConfiguration",
            "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeImageAutoConfiguration",
            "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeVideoAutoConfiguration",
            "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAudioSpeechAutoConfiguration",
            "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAudioTranscriptionAutoConfiguration",
            "com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeRerankAutoConfiguration"
    };

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        ClassLoader cl = application.getClassLoader();
        Set<String> toExclude = new LinkedHashSet<>();
        Map<String, Object> markers = new HashMap<>();

        boolean redisEnabled = environment.getProperty("wikiagent.redis.enabled", Boolean.class, false);
        if (!redisEnabled) {
            for (String className : REDIS_AUTO_CONFIGURATIONS) {
                if (ClassUtils.isPresent(className, cl)) {
                    toExclude.add(className);
                }
            }
        }

        // DashScope API Key 为空（开发/测试默认）时：排除 embedding 自动装配并打标记，
        // 由 DashScopeFallbackConfig 据标记装配 NoOpEmbeddingModel，保证上下文可启动。
        String apiKey = environment.getProperty("spring.ai.dashscope.api-key", "");
        if (apiKey == null || apiKey.isBlank()) {
            for (String className : DASHSCOPE_KEY_REQUIRED_AUTOCONFIGS) {
                if (ClassUtils.isPresent(className, cl)) {
                    toExclude.add(className);
                }
            }
            markers.put("wikiagent.internal.embedding-noop", "true");
        }

        if (toExclude.isEmpty() && markers.isEmpty()) {
            return;
        }

        Set<String> merged = new LinkedHashSet<>(toExclude);
        String existing = environment.getProperty("spring.autoconfigure.exclude");
        if (existing != null && !existing.isBlank()) {
            Arrays.stream(existing.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .forEach(merged::add);
        }

        Map<String, Object> overrides = new HashMap<>(markers);
        overrides.put("spring.autoconfigure.exclude", String.join(",", merged));
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, overrides));
    }
}
