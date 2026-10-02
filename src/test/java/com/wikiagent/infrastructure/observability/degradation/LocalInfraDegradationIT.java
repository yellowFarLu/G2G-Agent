package com.wikiagent.infrastructure.observability.degradation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.application.observability.circuit.CircuitBreakerRegistry;
import com.wikiagent.domain.observability.circuit.CircuitComponents;
import com.wikiagent.domain.observability.circuit.CircuitState;
import com.wikiagent.domain.task.FatalTaskException;
import com.wikiagent.domain.task.HumanRequiredException;
import com.wikiagent.domain.task.RetryableTaskException;
import com.wikiagent.domain.task.StepDef;
import com.wikiagent.domain.task.StepResult;
import com.wikiagent.domain.task.TaskExecutionContext;
import com.wikiagent.domain.task.TaskHandler;
import com.wikiagent.domain.task.TaskInstance;
import com.wikiagent.domain.task.TaskStatus;
import com.wikiagent.domain.task.ports.TaskDispatcherPort;
import com.wikiagent.domain.task.ports.TaskRepositoryPort;
import com.wikiagent.infrastructure.task.mq.LocalTaskDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-I4 ②③ Redis/RocketMQ 不可用降级集成测试（默认开发装配）：
 * <ul>
 *   <li>wikiagent.redis.enabled=false → 注册表 redis=OPEN（JVM 单机协调/单机限流生效）；</li>
 *   <li>wikiagent.task.mq=local → rocketmq=OPEN，TaskDispatcherPort 实际目标为 {@link LocalTaskDispatcher}，
 *       任务仍可在秒级完成（本地调度降级可用）。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class LocalInfraDegradationIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:file:./data/h2/test-local-degrade-" + System.nanoTime()
                        + ";AUTO_SERVER=TRUE;MODE=MySQL;LOCK_TIMEOUT=10000");
    }

    @TestConfiguration
    static class DegradeHandlerConfig {
        @Bean
        TaskHandler obsDegradeHandler() {
            return new TaskHandler() {
                @Override
                public String taskType() {
                    return "OBS_DEGRADE";
                }

                @Override
                public List<StepDef> planSteps(JsonNode payloadArgs) {
                    return List.of(StepDef.of(1, "DEGRADE", "降级链路任务"));
                }

                @Override
                public StepResult executeStep(TaskExecutionContext ctx)
                        throws RetryableTaskException, FatalTaskException, HumanRequiredException {
                    return StepResult.done(100);
                }
            };
        }
    }

    @Autowired
    private CircuitBreakerRegistry circuitRegistry;

    @Autowired
    private TaskDispatcherPort dispatcher;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TaskRepositoryPort taskRepo;

    @Test
    void 默认装配下redis与rocketmq处于降级态但本地调度任务可完成() throws Exception {
        // ② Redis 未启用 → 单机协调/限流降级中
        assertThat(circuitRegistry.state(CircuitComponents.REDIS)).isEqualTo(CircuitState.OPEN);
        // ③ RocketMQ 未启用 → 本地调度降级中；其余三方默认 CLOSED
        assertThat(circuitRegistry.state(CircuitComponents.ROCKETMQ)).isEqualTo(CircuitState.OPEN);
        assertThat(circuitRegistry.state(CircuitComponents.PARSE)).isEqualTo(CircuitState.CLOSED);
        assertThat(circuitRegistry.state(CircuitComponents.MILVUS)).isEqualTo(CircuitState.CLOSED);

        // 端口代理的实际目标是本地调度器
        assertThat(Proxy.isProxyClass(dispatcher.getClass())).isTrue();
        Object handler = Proxy.getInvocationHandler(dispatcher);
        boolean localTarget = false;
        for (java.lang.reflect.Field f : handler.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            if (f.get(handler) instanceof LocalTaskDispatcher) {
                localTarget = true;
            }
        }
        assertThat(localTarget).as("TaskDispatcherPort 代理目标应为 LocalTaskDispatcher").isTrue();

        // 本地调度链路端到端：提交后秒级 COMPLETED
        String bizKey = "obs-degrade-" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("taskType", "OBS_DEGRADE", "bizKey", bizKey))))
                .andExpect(status().isOk())
                .andReturn();
        String taskId = JSON.readTree(result.getResponse().getContentAsString()).get("taskId").asText();
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            TaskInstance task = taskRepo.findByTaskId(taskId).orElse(null);
            if (task != null && task.status() == TaskStatus.COMPLETED) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("本地调度任务未在 15s 内完成: " + taskId);
    }
}
