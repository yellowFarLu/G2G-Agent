package com.wikiagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 子项目 A Task 1：{@link TaskProperties} 默认值与绑定测试。
 * 默认值逐字对应设计规格第 6 节 yaml（框架默认开启 + mq=local，零外部依赖）。
 */
class TaskPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(EnableTaskProperties.class);

    @EnableConfigurationProperties(TaskProperties.class)
    static class EnableTaskProperties {
    }

    @Test
    void defaultsMatchSpecSectionSix() {
        contextRunner.run(context -> {
            TaskProperties props = context.getBean(TaskProperties.class);

            // 框架默认开，配合 mq=local 零外部依赖
            assertThat(props.isEnabled()).isTrue();
            assertThat(props.getMq()).isEqualTo("local");

            // 租约/心跳/扫描周期
            assertThat(props.getLeaseTtlSec()).isEqualTo(30);
            assertThat(props.getHeartbeatSec()).isEqualTo(10);
            assertThat(props.getRecoveryScanSec()).isEqualTo(15);
            assertThat(props.getDispatchRetryScanSec()).isEqualTo(5);

            // 同租户并发上限：0 表示不限
            assertThat(props.getTenantMaxConcurrent()).isZero();

            // 消费组并发度
            assertThat(props.getConcurrency()).isNotNull();
            assertThat(props.getConcurrency().getIngest()).isEqualTo(2);
            assertThat(props.getConcurrency().getAgent()).isEqualTo(4);

            // 默认重试/超时
            assertThat(props.getDefaults()).isNotNull();
            assertThat(props.getDefaults().getMaxAttempts()).isEqualTo(3);
            assertThat(props.getDefaults().getStepTimeoutSec()).isEqualTo(300);
            assertThat(props.getDefaults().getAgentStepTimeoutSec()).isEqualTo(180);
            assertThat(props.getDefaults().getDeadlineSec()).isEqualTo(1800);

            // 指数退避 10s/30s/2min
            assertThat(props.getBackoff())
                    .containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(2));
        });
    }

    @Test
    void bindsOverridesFromRelaxedProperties() {
        contextRunner
                .withPropertyValues(
                        "wikiagent.task.enabled=false",
                        "wikiagent.task.mq=rocketmq",
                        "wikiagent.task.tenant-max-concurrent=8",
                        "wikiagent.task.backoff=5s,15s,1m,5m",
                        "wikiagent.task.defaults.max-attempts=5",
                        "wikiagent.task.concurrency.ingest=7")
                .run(context -> {
                    TaskProperties props = context.getBean(TaskProperties.class);
                    assertThat(props.isEnabled()).isFalse();
                    assertThat(props.getMq()).isEqualTo("rocketmq");
                    assertThat(props.getTenantMaxConcurrent()).isEqualTo(8);
                    assertThat(props.getBackoff()).isEqualTo(
                            List.of(Duration.ofSeconds(5), Duration.ofSeconds(15),
                                    Duration.ofMinutes(1), Duration.ofMinutes(5)));
                    assertThat(props.getDefaults().getMaxAttempts()).isEqualTo(5);
                    assertThat(props.getConcurrency().getIngest()).isEqualTo(7);
                });
    }
}
