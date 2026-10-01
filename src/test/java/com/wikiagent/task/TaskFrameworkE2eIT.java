package com.wikiagent.task;

import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.wikiagent.application.task.TaskRecoveryJob;
import com.wikiagent.repo.KbChildChunkRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 13 端到端验收 IT（Testcontainers：MySQL 8 + Redis 7 + RocketMQ 5.3.1 真实容器）。
 * 场景：① INGEST txt 上传 → COMPLETED → doc READY + child 行数；
 * ② 重复上传幂等（duplicate=true、同一 taskId）；
 * ③ AGENT stub 任务暂停/恢复闭环（planner 不被再次调用）；
 * ④ 崩溃恢复：租约置为过期 + 恢复扫描回收 → RETRY → 断点续跑 COMPLETED。
 * 需 Docker 环境，mvn verify 执行；mvn test（surefire 排除 *IT）不加载本类。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TaskFrameworkE2eIT.EnvInitializer.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class TaskFrameworkE2eIT {

    private static final DockerImageName ROCKETMQ_IMAGE = DockerImageName.parse("apache/rocketmq:5.3.1");
    private static final Network NET = Network.newNetwork();

    /** NameServer：固定映射 9876，宿主侧客户端经 127.0.0.1 访问。 */
    private static final GenericContainer<?> NAMESRV = new GenericContainer<>(ROCKETMQ_IMAGE)
            .withNetwork(NET)
            .withNetworkAliases("namesrv")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(HostConfig.newHostConfig()
                    .withPortBindings(PortBinding.parse("9876:9876"))))
            .withExposedPorts(9876)
            .withEnv("JAVA_OPT_EXT", "-Xms256m -Xmx256m")
            .withCommand("sh", "mqnamesrv")
            .waitingFor(Wait.forLogMessage(".*Boot success.*", 1))
            .withStartupTimeout(java.time.Duration.ofMinutes(3));

    /**
     * Broker：brokerIP1=127.0.0.1 使 NameServer 返回宿主可达地址（客户端在本机 JVM），
     * 固定映射 10911/10909；与 NameServer 同网络（经别名解析 namesrvAddr）。
     */
    private static final GenericContainer<?> BROKER = new GenericContainer<>(ROCKETMQ_IMAGE)
            .withNetwork(NET)
            .withNetworkAliases("broker")
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(HostConfig.newHostConfig()
                    .withPortBindings(PortBinding.parse("10911:10911"), PortBinding.parse("10909:10909"))))
            .withExposedPorts(10911, 10909)
            .withEnv("JAVA_OPT_EXT", "-Xms512m -Xmx512m -Xmn128m")
            .withCommand("sh", "-c",
                    "printf 'brokerClusterName=DefaultCluster\\nbrokerName=broker-a\\nbrokerId=0\\n"
                            + "brokerIP1=127.0.0.1\\nnamesrvAddr=namesrv:9876\\n' > /home/rocketmq/e2e-broker.conf"
                            + " && sh mqbroker -c /home/rocketmq/e2e-broker.conf")
            .waitingFor(Wait.forLogMessage(".*Boot success.*", 1))
            .withStartupTimeout(java.time.Duration.ofMinutes(3));

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("wikiagent")
            .withUsername("test")
            .withPassword("test");

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        NAMESRV.start();
        BROKER.start();
        MYSQL.start();
        REDIS.start();
    }

    static class EnvInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            TestPropertyValues.of(
                    "MYSQL_URL=" + MYSQL.getJdbcUrl(),
                    "MYSQL_USER=test",
                    "MYSQL_PASSWORD=test",
                    "REDIS_HOST=" + REDIS.getHost(),
                    "REDIS_PORT=" + REDIS.getMappedPort(6379),
                    "TASK_MQ=rocketmq",
                    "ROCKETMQ_NAME_SERVER=127.0.0.1:9876",
                    "WIKIAGENT_TASK_ENABLED=true"
            ).applyTo(context.getEnvironment());
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private static final String PLAN_JSON = """
            [{"id":"step1","goal":"检索商家入驻流程","stepType":"search_kb"},
             {"id":"step2","goal":"汇总生成答案","stepType":"generate"}]""";
    private static final String ACTION_JSON =
            "{\"thought\":\"检索\",\"action\":{\"name\":\"search_knowledge_base\",\"args\":\"商家入驻\"},"
                    + "\"finalAnswer\":null}";
    private static final String FINAL_JSON =
            "{\"thought\":\"已获得答案\",\"action\":null,\"finalAnswer\":\"商家入驻流程：注册-审核-上线\"}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TaskRecoveryJob recoveryJob;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KbChildChunkRepo childRepo;

    @MockBean
    private ChatModel chatModel;

    @MockBean
    private com.wikiagent.service.store.MilvusStoreService milvus;

    @MockBean
    private org.springframework.ai.embedding.EmbeddingModel embeddingModel;

    @MockBean
    private com.wikiagent.application.knowledge.KnowledgeTaggingService taggingService;

    private final AtomicInteger plannerCalls = new AtomicInteger();
    private final AtomicBoolean blockFirstReAct = new AtomicBoolean(false);
    private final AtomicBoolean blockEmbed = new AtomicBoolean(false);
    private final AtomicBoolean reActActionUsed = new AtomicBoolean(false);
    private CountDownLatch reActEntered = new CountDownLatch(1);
    private CountDownLatch reActRelease = new CountDownLatch(1);
    private CountDownLatch embedRelease = new CountDownLatch(1);
    private final List<String> createdDocIds = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUpStubs() {
        plannerCalls.set(0);
        blockFirstReAct.set(false);
        blockEmbed.set(false);
        reActActionUsed.set(false);
        reActEntered = new CountDownLatch(1);
        reActRelease = new CountDownLatch(1);
        embedRelease = new CountDownLatch(1);
        when(embeddingModel.embed(anyList())).thenAnswer(this::stubEmbed);
        when(chatModel.call(any(Prompt.class))).thenAnswer(this::stubChat);
    }

    @AfterEach
    void releaseAndCleanup() {
        // 释放所有阻塞桩，避免僵尸 worker 线程跨测试悬挂
        reActRelease.countDown();
        embedRelease.countDown();
        createdDocIds.clear();
    }

    /** EMBED 桩：首轮可阻塞（场景④制造 worker 卡在 EMBED 步骤的租约窗口）。 */
    @SuppressWarnings("unchecked")
    private Object stubEmbed(InvocationOnMock inv) {
        if (blockEmbed.compareAndSet(true, false)) {
            try {
                embedRelease.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        List<String> texts = (List<String>) inv.getArgument(0);
        return texts.stream().map(t -> new float[8]).toList();
    }

    /** 按系统提示词路由 stub：规划 / 反思 / 失败反思 / ReAct（首轮可阻塞）。 */
    private ChatResponse stubChat(InvocationOnMock inv) {
        Prompt prompt = inv.getArgument(0);
        List<Message> messages = prompt.getInstructions();
        String system = messages.get(0).getText();
        if (system.contains("任务规划")) {
            plannerCalls.incrementAndGet();
            return chatResponse(PLAN_JSON);
        }
        if (system.contains("反思器")) {
            return chatResponse("{\"text\":\"ok\",\"needsRework\":false,\"reworkHint\":\"\","
                    + "\"planAdjustments\":[]}");
        }
        if (system.contains("失败反思器")) {
            return chatResponse("{\"text\":\"ok\",\"shouldRetry\":false,\"reason\":\"不再重试\"}");
        }
        reActEntered.countDown();
        if (blockFirstReAct.compareAndSet(true, false)) {
            try {
                reActRelease.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (reActActionUsed.compareAndSet(false, true)) {
            return chatResponse(ACTION_JSON);
        }
        return chatResponse(FINAL_JSON);
    }

    /** ① INGEST txt 上传 → COMPLETED → doc READY + child 行数 > 0。 */
    @Test
    void ingestTxtUploadCompletesAndSearchableShape() throws Exception {
        Uploaded up = uploadTxt("e2e-ingest.txt", "ingest-user-" + uid());

        await(() -> "COMPLETED".equals(taskStatus(up.taskId)), "入库任务 COMPLETED");
        mvc.perform(get("/api/documents/" + up.docId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
        assertThat(childRepo.findByDocId(up.docId)).isNotEmpty();
    }

    /** ② 相同内容重复上传 → duplicate=true + 同一 taskId（bizKey 幂等）。 */
    @Test
    void duplicateUploadReusesSameTask() throws Exception {
        String user = "dup-user-" + uid();
        String content = "E2E duplicate upload check for task framework. ".repeat(30);
        Uploaded first = uploadTxt("e2e-dup.txt", user, content.getBytes());
        await(() -> "COMPLETED".equals(taskStatus(first.taskId)), "首次入库 COMPLETED");

        mvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "e2e-dup.txt", "text/plain",
                                content.getBytes()))
                        .header("X-User-Id", user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.taskId").value(first.taskId()));
    }

    /** ③ AGENT stub 任务：RUNNING 中暂停 → SUSPENDED → 恢复 → COMPLETED，planner 仅一次。 */
    @Test
    void agentTaskSuspendResumeRoundTrip() throws Exception {
        blockFirstReAct.set(true);
        String taskId = submitAgentTask("e2e-agent-" + uid());

        await(() -> reActEntered.getCount() == 0, "NODE_1 ReAct 首次调用进入");
        mvc.perform(post("/api/tasks/" + taskId + "/suspend")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        reActRelease.countDown();
        await(() -> "SUSPENDED".equals(taskStatus(taskId)), "任务暂停");

        mvc.perform(post("/api/tasks/" + taskId + "/resume")).andExpect(status().isOk());
        await(() -> "COMPLETED".equals(taskStatus(taskId)), "恢复后完成");

        // 恢复不得重新规划（Review Focus #5）
        assertThat(plannerCalls.get()).isEqualTo(1);
        assertThat(eventTypes(taskId)).contains("SUSPEND", "RESUME");
    }

    /** ④ 崩溃恢复：EMBED 卡住 → 租约置过期 → 恢复扫描回收（RETRY）→ 断点续跑 COMPLETED。 */
    @Test
    void leaseExpiryRecoveredWithinBackoffAndResumes() throws Exception {
        blockEmbed.set(true);
        Uploaded up = uploadTxt("e2e-recovery.txt", "recovery-user-" + uid());

        await(() -> "RUNNING".equals(taskStatus(up.taskId)), "任务进入 RUNNING");
        jdbc.update("UPDATE task_instance SET lease_expire_at = ? WHERE task_id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), up.taskId);
        recoveryJob.runOnce();

        await(() -> "COMPLETED".equals(taskStatus(up.taskId)), "回收后断点续跑 COMPLETED");
        assertThat(eventTypes(up.taskId)).contains("RETRY");
        mvc.perform(get("/api/documents/" + up.docId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
    }

    // ===== 基础设施 =====

    private record Uploaded(String docId, String taskId) {
    }

    private Uploaded uploadTxt(String filename, String userId) throws Exception {
        return uploadTxt(filename, userId,
                "Task framework E2E ingest sample. 商家入驻流程包括注册、审核、上线三个阶段。".repeat(40)
                        .getBytes());
    }

    private Uploaded uploadTxt(String filename, String userId, byte[] bytes) throws Exception {
        var node = JSON.readTree(mvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", filename, "text/plain", bytes))
                        .header("X-User-Id", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(org.hamcrest.Matchers.startsWith("tsk_")))
                .andReturn().getResponse().getContentAsString());
        String docId = node.get("id").asText();
        createdDocIds.add(docId);
        return new Uploaded(docId, node.get("taskId").asText());
    }

    private String submitAgentTask(String userId) throws Exception {
        String body = JSON.createObjectNode()
                .put("taskType", "AGENT")
                .put("bizKey", "agent:" + userId + ":e2e-sess:" + UUID.randomUUID())
                .set("args", JSON.createObjectNode()
                        .put("userId", userId)
                        .put("sessionId", "e2e-sess")
                        .put("userInput", "介绍一下商家入驻流程"))
                .toString();
        MvcResult result = mvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString()).get("taskId").asText();
    }

    private String taskStatus(String taskId) {
        try {
            return JSON.readTree(mvc.perform(get("/api/tasks/" + taskId)).andReturn()
                    .getResponse().getContentAsString()).get("status").asText();
        } catch (Exception e) {
            return "";
        }
    }

    private List<String> eventTypes(String taskId) {
        try {
            return JSON.readTree(mvc.perform(get("/api/tasks/" + taskId + "/events")).andReturn()
                    .getResponse().getContentAsString())
                    .findValuesAsText("eventType");
        } catch (Exception e) {
            return List.of();
        }
    }

    private static void await(BooleanSupplier cond, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(150);
        }
        org.junit.jupiter.api.Assertions.fail("等待超时: " + what);
    }

    private static String uid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
