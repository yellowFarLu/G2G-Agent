package com.wikiagent.infrastructure.observability.trace;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.application.llm.ModelCallRecorder;
import com.wikiagent.domain.llm.ModelCallLog;
import com.wikiagent.domain.llm.ModelCallLogPurpose;
import com.wikiagent.domain.llm.ModelCallLogRepository;
import com.wikiagent.domain.task.HumanRequiredException;
import com.wikiagent.domain.task.RetryableTaskException;
import com.wikiagent.domain.task.FatalTaskException;
import com.wikiagent.domain.task.StepDef;
import com.wikiagent.domain.task.StepResult;
import com.wikiagent.domain.task.TaskExecutionContext;
import com.wikiagent.domain.task.TaskHandler;
import com.wikiagent.domain.task.TaskInstance;
import com.wikiagent.domain.task.TaskStatus;
import com.wikiagent.domain.task.ports.TaskRepositoryPort;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-I1 硬证据集成测试（H2 真实迁移 + mq=local）：
 * <ol>
 *   <li>POST /api/tasks 响应头含 X-Trace-Id；</li>
 *   <li>任务 worker / stepPool 线程日志 MDC、步骤内打点写入的 model_call_log.trace_id
 *       与触发请求同 traceId；task_event 按 taskId 与该 traceId 同源串联；</li>
 *   <li>POST /api/chat（SSE，J 代理接线前）至少保证响应头回写 X-Trace-Id。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class TracePropagationIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:file:./data/h2/test-obs-trace-" + System.nanoTime()
                        + ";AUTO_SERVER=TRUE;MODE=MySQL;LOCK_TIMEOUT=10000");
    }

    /** 观测用 handler：步骤线程读取 MDC traceId 并写一行 model_call_log。 */
    @TestConfiguration
    static class ObsHandlerConfig {
        @Bean
        TaskHandler obsTraceHandler(ModelCallRecorder recorder) {
            return new TaskHandler() {
                @Override
                public String taskType() {
                    return "OBS_TRACE";
                }

                @Override
                public List<StepDef> planSteps(com.fasterxml.jackson.databind.JsonNode payloadArgs) {
                    return List.of(StepDef.of(1, "TRACE", "链路打点"));
                }

                @Override
                public StepResult executeStep(TaskExecutionContext ctx)
                        throws RetryableTaskException, FatalTaskException, HumanRequiredException {
                    String traceId = org.slf4j.MDC.get("traceId");
                    ObsTraceHolder.TRACE_BY_TASK.put(ctx.taskId(), traceId);
                    recorder.record(ModelCallLogPurpose.CHAT, "obs-stub", "obs-model",
                            3, 5, 7L, true, null, "obs-user", "obs-session");
                    return StepResult.done(100, "obs-ref");
                }
            };
        }
    }

    /** handler 与测试主线程间的观测载体。 */
    static final class ObsTraceHolder {
        static final Map<String, String> TRACE_BY_TASK = new ConcurrentHashMap<>();
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TaskRepositoryPort taskRepo;

    @Autowired
    private ModelCallLogRepository callLogRepo;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 任务链路响应头_日志MDC_model_call_log_task_event同traceId串联() throws Exception {
        Logger workerLogger = (Logger) LoggerFactory.getLogger("com.wikiagent.application.task.TaskWorker");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        workerLogger.addAppender(appender);

        String bizKey = "obs-trace-" + UUID.randomUUID().toString().substring(0, 8);
        String body = JSON.writeValueAsString(Map.of("taskType", "OBS_TRACE", "bizKey", bizKey));
        try {
            MvcResult result = mvc.perform(post("/api/tasks")
                            .header("X-User-Id", "obs-user")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Trace-Id", org.hamcrest.Matchers.startsWith("tr-")))
                    .andReturn();
            String traceId = result.getResponse().getHeader("X-Trace-Id");
            String taskId = JSON.readTree(result.getResponse().getContentAsString()).get("taskId").asText();

            // 等任务跑完（local mq，毫秒级）
            await(() -> taskRepo.findByTaskId(taskId).map(TaskInstance::status)
                    .filter(s -> s == TaskStatus.COMPLETED).isPresent(), 15_000);

            // ② stepPool 线程内 MDC traceId 与请求同源
            assertThat(ObsTraceHolder.TRACE_BY_TASK).containsEntry(taskId, traceId);

            // ② model_call_log 行携带同 traceId
            List<ModelCallLog> rows = callLogRepo.findByTraceId(traceId);
            assertThat(rows).isNotEmpty();
            assertThat(rows).allSatisfy(r -> {
                assertThat(r.traceId()).isEqualTo(traceId);
                assertThat(r.provider()).isEqualTo("obs-stub");
            });

            // task_event 行按 taskId 落库（与 traceId 同一次执行：worker 日志 MDC 证据见下）
            Integer events = jdbc.queryForObject(
                    "select count(*) from task_event where task_id = ?", Integer.class, taskId);
            assertThat(events).isNotNull().isGreaterThanOrEqualTo(3);

            // ③ 日志层：worker 线程存在携带同 traceId MDC 的日志行
            assertThat(appender.list).anySatisfy(e -> {
                assertThat(e.getMDCPropertyMap()).containsEntry("traceId", traceId);
                assertThat(e.getFormattedMessage()).contains(taskId);
            });
        } finally {
            workerLogger.detachAppender(appender);
        }
    }

    @Test
    void chat端点响应头回写traceId且agent_trace按同traceId落行() throws Exception {
        // ChatService 在 @Async 边界前捕获 MDC 快照并在工作线程恢复（J 接线后），
        // RagTraceRecorder 持久化 agent_trace 时从 MDC 取 traceId（V16 列）
        MvcResult result = mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", org.hamcrest.Matchers.startsWith("tr-")))
                .andReturn();
        String traceId = result.getResponse().getHeader("X-Trace-Id");

        // 异步处理落 span：轮询 agent_trace 直到出现同 traceId 行
        await(() -> {
            Integer rows = jdbc.queryForObject(
                    "select count(*) from agent_trace where trace_id = ?", Integer.class, traceId);
            return rows != null && rows > 0;
        }, 15_000);
    }

    /** 上游 traceId 头透传：外部网关注入的 traceId 原样进入任务链路。 */
    @Test
    void 外部传入XTraceId原样透传到任务链路() throws Exception {
        String upstream = "gw-" + UUID.randomUUID().toString().substring(0, 8);
        String bizKey = "obs-trace-up-" + UUID.randomUUID().toString().substring(0, 8);
        String body = JSON.writeValueAsString(Map.of("taskType", "OBS_TRACE", "bizKey", bizKey));
        MvcResult result = mvc.perform(post("/api/tasks")
                        .header("X-Trace-Id", upstream)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", upstream))
                .andReturn();
        String taskId = JSON.readTree(result.getResponse().getContentAsString()).get("taskId").asText();
        await(() -> taskRepo.findByTaskId(taskId).map(TaskInstance::status)
                .filter(s -> s == TaskStatus.COMPLETED).isPresent(), 15_000);
        assertThat(ObsTraceHolder.TRACE_BY_TASK).containsEntry(taskId, upstream);
        assertThat(callLogRepo.findByTraceId(upstream)).isNotEmpty();
    }

    private static void await(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待条件超时");
    }

    @FunctionalInterface
    interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
