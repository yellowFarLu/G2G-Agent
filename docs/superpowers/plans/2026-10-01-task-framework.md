# 子项目 A：任务与数据链基座 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建成 MySQL 状态机 + RocketMQ 投递 + Redis 协调的通用任务框架，并首批接入文档入库与 Agent(PERO) 两条真实链路，交接清单从 todo.json 迁至 MySQL。

**Architecture:** 任务真相以 MySQL 五张任务表 + 四张交接清单表为准；RocketMQ 单 Topic（`TASK_DISPATCH`）Tag 区分 INGEST/AGENT、独立消费组、延迟等级退避；Redis 仅做租约/控制标志/进度/SSE 桥的协调态，缺失时回退 JVM/MySQL 实现。Worker  CAS 抢租约、步骤级执行、检查点响应暂停取消、崩溃扫描恢复。

**Tech Stack:** Java 21、Spring Boot 3.5.16、JPA/Flyway（开发 H2 MODE=MySQL，生产 MySQL 8）、`org.apache.rocketmq:rocketmq-client:5.3.1`（经典 Remoting 协议，避开 rocketmq-spring-starter 的 javax 兼容问题）、Redisson 3.45、Testcontainers（CI）。

**Spec:** [docs/superpowers/specs/2026-10-01-task-framework-design.md](../specs/2026-10-01-task-framework-design.md)

## Global Constraints

- Java 21 语法；包根 `com.wikiagent`；DDD 四层位置：接口层 `interfaces/task`、应用层 `application/task`、领域层 `domain/task`、基础设施层 `infrastructure/task/{jpa,redis,mq,stream}`，交接清单落 `infrastructure/memory/mysql`。
- 任务框架所有 Bean 加 `@ConditionalOnProperty(name="wikiagent.task.enabled", havingValue="true")`（PERO 子系统同款约定）。
- Flyway 文件 `V9__task_framework.sql` 同时兼容 H2（MODE=MySQL）与 MySQL 8：JSON 列一律 `TEXT`、时间列 `TIMESTAMP NULL`（应用侧显式赋值，不用 DEFAULT CURRENT_TIMESTAMP）、索引沿用 V8 风格 `CREATE INDEX IF NOT EXISTS`。
- RocketMQ 延迟等级映射（经典 broker 默认）：10s=level 3、30s=level 4、2min=level 6；退避数组 `[10s, 30s, 2m]`。
- 状态/错误码等字符串只能取自本计划定义的枚举，不得硬编码裸字符串。
- 不得删除或破坏现有同步链路（`wikiagent.task.enabled=false` 时上传与 PERO 行为不变）。
- 每个任务结束必须单独 commit；commit message 用 Conventional Commits 且含中文描述（`feat(task): …`、`refactor(task): …`、`test(task): …`、`chore(task): …`）。
- 不引入除 rocketmq-client、testcontainers（test）之外的新依赖；不引入 Lombok。引入前先跑 `mvn -q compile` 确认坐标可解析。
- 命名/字段必须与规格第 2 节表结构一致：`task_instance/task_step/task_event/human_task/handover_checklist/handover_node/handover_abandoned_path/handover_data_ref`。

## File Structure

```
src/main/java/com/wikiagent/
├── config/TaskProperties.java                         # @ConfigurationProperties("wikiagent.task")
├── domain/task/
│   ├── TaskStatus.java        enum（9 态）
│   ├── StepStatus.java        enum PENDING/RUNNING/DONE/SKIPPED/FAILED
│   ├── TaskEventType.java     enum（16 事件）
│   ├── ActorType.java         enum SYSTEM/USER/WORKER
│   ├── ErrorCode.java         enum（7 码）+ retryable()
│   ├── HumanTaskKind.java     enum INPUT/DIRECT_RESOLVE
│   ├── HumanTaskStatus.java   enum OPEN/CLAIMED/RESOLVED/EXPIRED
│   ├── ControlFlag.java       record/singleton enum NONE/PAUSE/CANCEL
│   ├── IllegalStateTransitionException.java
│   ├── TaskStateMachine.java  # 纯逻辑：assertTransition(from,event,to) + canLease()
│   ├── TaskInstance.java      record（规格 2.1 全字段）
│   ├── TaskStep.java          record（规格 2.2）
│   ├── TaskEvent.java         record（规格 2.3）
│   ├── HumanTask.java         record（规格 2.4）
│   ├── StepDef.java           record(int no, String type, String name, boolean humanCheckpoint, Integer timeoutSec)
│   ├── Checkpoint.java        record(String json)
│   ├── StepResult.java        record(boolean skip, String checkpointJson, int progressPercent, String resultRef)
│   ├── TaskPayload.java       record(String taskType, String bizKey, String submittedBy, String tenantId,
│   │                                   String idempotencyKey, com.fasterxml.jackson.databind.JsonNode args,
│   │                                   Integer maxAttempts, Integer deadlineSec)
│   ├── TaskExecutionContext.java  # 参数对象 + 动态步骤注册（AGENT 用）
│   ├── TaskHandler.java       interface
│   ├── RetryableTaskException.java / FatalTaskException.java / HumanRequiredException.java
│   ├── ControlSignalException.java + PauseSignalException.java + CancelSignalException.java
│   └── ports/
│       ├── TaskRepositoryPort.java
│       ├── TaskStepRepositoryPort.java
│       ├── TaskEventRepositoryPort.java
│       ├── HumanTaskRepositoryPort.java
│       ├── LeasePort.java
│       ├── ControlFlagPort.java
│       ├── ProgressPort.java
│       └── TaskDispatcherPort.java
├── application/task/
│   ├── TaskIds.java                  # tsk_yyyyMMddHHmmss_6位
│   ├── Backoff.java                  # delayLevel/duration for attempt
│   ├── ErrorClassifier.java          # Exception -> ErrorCode + retryable
│   ├── OptimisticControlConflictException.java
│   ├── TaskWatchdog.java             # 领取时延迟消息，到点核对租约（recovery 双保险）
│   ├── TaskSubmissionService.java
│   ├── TaskHandlerRegistry.java
│   ├── TaskWorker.java               # 核心执行器（与 MQ 解耦）
│   ├── TaskControlService.java
│   ├── HumanTaskService.java
│   ├── TaskQueryService.java
│   ├── DispatchCompensationJob.java
│   ├── TaskRecoveryJob.java
│   ├── TaskStreamBus.java             # interface（delta/done/error + progress）
│   └── handler/
│       ├── IngestTaskHandler.java
│       └── AgentTaskHandler.java
├── interfaces/task/
│   ├── TaskController.java
│   ├── HumanTaskController.java
│   └── dto/*.java
└── infrastructure/
    ├── task/jpa/   {TaskInstanceEntity,TaskStepEntity,TaskEventEntity,HumanTaskEntity}.java + 各自 Dao + Adapter
    ├── task/redis/ {RedisLeasePort,RedisControlFlagPort,RedisProgressPort,RedisTaskStreamBus,
    │                JvmLeasePort,JvmControlFlagPort,JvmProgressPort,InProcessTaskStreamBus, TaskRedisConfig}
    └── task/mq/     {RocketMqConfig,RocketMqTaskDispatcher,RocketMqTaskConsumer,LocalTaskDispatcher,
                      RocketMqHealthIndicator,TaskDlqConsumer}

src/main/java/com/wikiagent/infrastructure/memory/mysql/
├── HandoverChecklistEntity / HandoverNodeEntity / HandoverAbandonedPathEntity / HandoverDataRefEntity.java
├── MysqlHandoverRepository.java       # implements domain.memory.HandoverRepository
├── DataRefValueResolver.java          # 确定性解析 [DATA:xxx]
└── HandoverFileMigrationRunner.java   # CommandLineRunner，开关控制

src/test/java/com/wikiagent/... 对应测试；回归样本 src/test/resources/task-samples/legacy.doc（PDF/xlsx 由测试内 PDFBox/POI 动态生成）
src/test/resources/db/migration 无需（复用主迁移）
```

注：`application/agent/pero/` 下仅最小改动：`PeroAgent.java` 抽出循环钩子、`ReActExecutor.java` 加一行控制检查；不新写 PERO 变体。

---

## Task 1: 依赖、配置与 RocketMQ 容器

**Files:**
- Modify: `pom.xml`（properties/dependencies）
- Modify: `docker-compose.yml`（nameserver/broker/dashboard 三服务，app 依赖与环境变量）
- Modify: `.env.example`
- Create: `src/main/java/com/wikiagent/config/TaskProperties.java`
- Create: `src/test/java/com/wikiagent/config/TaskPropertiesTest.java`

**Interfaces:**
- Produces: `TaskProperties` 暴露 `boolean enabled=true; String mq="local"; Concurrency concurrency; int leaseTtlSec=30; int heartbeatSec=10; int recoveryScanSec=15; int dispatchRetryScanSec=5; int tenantMaxConcurrent=0; Defaults defaults; List<Duration> backoff;`，内嵌 `Concurrency{int ingest=2;int agent=4;}` 与 `Defaults{int maxAttempts=3;int stepTimeoutSec=300;int agentStepTimeoutSec=180;int deadlineSec=1800;}`，字段名与规格第 6 节 yaml 逐字对应（tenant-max-concurrent 为规格"同租户并发上限可配"的落地）；`wikiagent.task` 配置根。

- [ ] **Step 1: 写失败测试** — `TaskPropertiesTest`：绑定一个 `Environment`/`Binder`（或 `ApplicationContextRunner` 带 `@EnableConfigurationProperties(TaskProperties.class)`），断言默认值 `enabled=true`（框架默认开，配合 mq=local 零外部依赖）、`mq="local"`（开发默认本地调度，docker/production 切 rocketmq）、`leaseTtlSec=30`、`heartbeatSec=10`、`recoveryScanSec=15`、`dispatchRetryScanSec=5`、`defaults.maxAttempts=3`、`stepTimeoutSec=300`、`agentStepTimeoutSec=180`、`deadlineSec=1800`、`concurrency.ingest=2`、`concurrency.agent=4`、`tenantMaxConcurrent=0`（不限）、`backoff=[10s,30s,2m]`。
- [ ] **Step 2: 跑测试确认失败** — `mvn -q -Dtest=TaskPropertiesTest test`，Expected: 编译失败/类不存在。
- [ ] **Step 3: 实现 `TaskProperties`** — `@ConfigurationProperties(prefix="wikiagent.task")` + 普通 getter/setter（不引入 Lombok），默认值在字段初始化处给出。
- [ ] **Step 4: 加依赖** — pom properties 加 `<rocketmq.version>5.3.1</rocketmq.version>`、`<testcontainers.version>1.20.4</testcontainers.version>`；dependencies 加 `org.apache.rocketmq:rocketmq-client:${rocketmq.version}`；dependencyManagement import `org.testcontainers:testcontainers-bom:${testcontainers.version}`，test 依赖加 `org.testcontainers:junit-jupiter`、`org.testcontainers:mysql`。先执行 `mvn -q dependency:resolve -DincludeScope=test` 验证坐标可解析。
- [ ] **Step 5: 注册配置** — `WikiAgentApplication` 或现有 `@Configuration` 上确认 `@EnableConfigurationProperties(TaskProperties.class)`；`application.yml` 加规格第 6 节的 `wikiagent.task` 段（本地默认 `enabled: true`、`mq: ${TASK_MQ:local}`、`tenant-max-concurrent: 0`），并把 `wikiagent.memory.handover-adapter: mysql`、`file-migration.enabled: false`（开发默认；docker compose 环境置 true）写入 yml。
- [ ] **Step 6: docker-compose 加 RocketMQ** — 加 `rmqnamesrv`（`apache/rocketmq:5.3.1`，`sh mqnamesrv`，端口 9876）、`rmqbroker`（同镜像，`sh mqbroker -n rmqnamesrv:9876 --enable-proxy`，配 `NAMESRV_ADDR=rmqnamesrv:9876`，端口 8080/8081，依赖 namesrv healthy）；两个 app 服务 environment 加 `TASK_MQ=rocketmq`、`ROCKETMQ_NAME_SERVER=rmqnamesrv:9876`、`WIKIAGENT_TASK_ENABLED=true`、`WIKIAGENT_MEMORY_HANDOVER_ADAPTER=mysql`、`WIKIAGENT_MEMORY_FILE_MIGRATION_ENABLED=true`，depends_on 增加 broker service_started。`.env.example` 加同名四个变量。
- [ ] **Step 7: 验证** — `mvn -q -Dtest=TaskPropertiesTest test` 通过；`mvn -q compile` 通过；`docker-compose config >/dev/null` 校验 compose 语法。
- [ ] **Step 8: Commit** — `chore(task): 新增任务框架配置项、RocketMQ 依赖与容器编排`。

---

## Task 2: 领域枚举与任务状态机

**Files:**
- Create: `domain/task/{TaskStatus,StepStatus,TaskEventType,ActorType,ErrorCode,HumanTaskKind,HumanTaskStatus,ControlFlag}.java`
- Create: `domain/task/{IllegalStateTransitionException,TaskStateMachine}.java`
- Test: `src/test/java/com/wikiagent/domain/task/TaskStateMachineTest.java`、`ErrorCodeTest.java`

**Interfaces:**
- Produces:
  - `enum TaskStatus { PENDING, DISPATCH, RUNNING, SUSPENDED, WAITING_HUMAN, CANCELING, COMPLETED, FAILED, CANCELLED }`，含 `boolean isTerminal()`（仅 COMPLETED/FAILED/CANCELLED 为 true）。
  - `enum ErrorCode { TIMEOUT,THIRD_PARTY_5XX,THIRD_PARTY_4XX,PARSE_FAILED,VALIDATION_FAILED,BUDGET_EXCEEDED,INTERNAL; boolean retryable()`：可重试 = TIMEOUT/THIRD_PARTY_5XX/PARSE_FAILED/INTERNAL。
  - `final class TaskStateMachine { static TaskStatus transition(TaskStatus from, TaskEventType event); }`，事件→目标按规格 1.2 转移表；非法转移抛 `IllegalStateTransitionException(from, event)`。允许的关键映射：SUBMIT→PENDING（from=null 新建场景用 `assertInitial`）；DISPATCH: PENDING→DISPATCH；LEASE: PENDING/DISPATCH→RUNNING；STEP_TO_WAIT（用 HUMAN_TAKE? 定义事件 `WAIT_HUMAN`）: RUNNING→WAITING_HUMAN；SUSPEND: RUNNING→SUSPENDED；RESUME: SUSPENDED/WAITING_HUMAN→PENDING；REQUEST_CANCEL: RUNNING/SUSPENDED→CANCELING；CANCEL: CANCELING→CANCELLED；RETRY: RUNNING→PENDING；COMPLETE: RUNNING→COMPLETED（WAITING_HUMAN+DIRECT_RESOLVE→COMPLETED 经 `HUMAN_RESOLVE`）；FAIL: RUNNING→FAILED；REPLAY: FAILED/CANCELLED→PENDING。
  - `TaskEventType` 必须含规格 2.3 的 16 个 + 本计划补充 `WAIT_HUMAN`。

- [ ] **Step 1: 写失败测试** — `TaskStateMachineTest` 用参数化测试逐行断言规格 1.2 全部合法迁移；另断言：RUNNING+SUSPEND→SUSPENDED、COMPLETED 接收任何事件抛异常、FAILED+REPLAY→PENDING、CANCELING+CANCEL→CANCELLED、WAITING_HUMAN+HUMAN_RESOLVE→COMPLETED、WAITING_HUMAN+RESUME→PENDING。`ErrorCodeTest` 断言 7 码的 retryable 布尔与规格 3.3 一致。
- [ ] **Step 2: 跑确认失败** — `mvn -q -Dtest=TaskStateMachineTest,ErrorCodeTest test`。
- [ ] **Step 3: 实现枚举与状态机** — 枚举按上；`TaskStateMachine.transition` 内部用 `Map<TransitionKey,TaskStatus>` 或 switch 穷尽匹配，无默认放行；非法一律抛异常（异常携带 from+event 文本）。
- [ ] **Step 4: 跑测试通过** — 同上命令 Expected: PASS。
- [ ] **Step 5: Commit** — `feat(task): 新增任务状态机、错误码与领域枚举`。

---

## Task 3: 领域模型与端口

**Files:**
- Create: `domain/task/{TaskInstance,TaskStep,TaskEvent,HumanTask,StepDef,Checkpoint,StepResult,TaskPayload,TaskExecutionContext}.java`
- Create: `domain/task/{TaskHandler,RetryableTaskException,FatalTaskException,HumanRequiredException,ControlSignalException,PauseSignalException,CancelSignalException}.java`
- Create: `domain/task/ports/{TaskRepositoryPort,TaskStepRepositoryPort,TaskEventRepositoryPort,HumanTaskRepositoryPort,LeasePort,ControlFlagPort,ProgressPort,TaskDispatcherPort}.java`
- Test: `src/test/java/com/wikiagent/domain/task/TaskExceptionConstructionTest.java`

**Interfaces:**
- Consumes: Task 2 枚举。
- Produces（后续任务直接依赖，签名冻结）:
  - `record TaskInstance(String taskId, String taskType, String bizKey, TaskStatus status, JsonNode payload, int attempt, int maxAttempts, int progressPercent, String resultRef, ErrorCode errorCode, String errorMsg, String idempotencyKey, String submittedBy, String tenantId, Instant enqueueAt, String leaseOwner, Instant leaseExpireAt, Instant heartbeatAt, Instant nextRunAt, String suspendReason, int controlVersion, Instant createdAt, Instant updatedAt)`，附 `withStatus(TaskStatus)` 等最小拷贝方法（手写，不用 Lombok）。
  - `record TaskStep(String taskId, int stepNo, String stepType, String stepName, StepStatus status, String checkpoint, Instant startedAt, Instant endedAt, String errorMsg)`。
  - `record TaskEvent(String taskId, TaskEventType eventType, ActorType actorType, String actorId, JsonNode detail, Instant createdAt)`。
  - `record HumanTask(Long id, String taskId, int stepNo, HumanTaskKind kind, String title, String instruction, JsonNode formSchema, JsonNode formValue, HumanTaskStatus status, String claimedBy, Instant claimedAt, String resolvedBy, Instant resolvedAt, int lockVersion, Instant createdAt)`。
  - `interface TaskHandler { String taskType(); List<StepDef> planSteps(JsonNode payloadArgs); StepResult executeStep(TaskExecutionContext ctx) throws RetryableTaskException,FatalTaskException,HumanRequiredException; default void onCancel(TaskExecutionContext ctx){} }`。
  - `TaskExecutionContext`：具体类（非 record，因含动态步骤注册），字段 `String taskId, String taskType, JsonNode args, Map<String,JsonNode> humanInputs, int attempt`（租约由 worker 持有，不放上下文），方法 `registerStep(StepDef def)`、`saveCheckpoint(String json)`、`checkpointOf(int stepNo)`、`requireArg(String key)`。
  - `record StepDef(int no, String type, String name, boolean humanCheckpoint, Integer timeoutSec)`（静态工厂 `of(no,type,name)`）。
  - `record StepResult(boolean skip, String checkpointJson, int progressPercent, String resultRef)`，静态 `done(int percent)`、`done(int percent, String ref)`、`checkpoint(String json)`。
  - `interface TaskDispatcherPort { void dispatch(String taskId, String taskType, int delayLevel); void dispatchWatchdog(String taskId, String ownerWorkerId, int delayLevel); }`（普通投递与看门狗投递；delayLevel=0 表示立即/按 Local 的 Duration 重载，Local 实现内部按 Backoff level→Duration 换算）。
  - `interface LeasePort { boolean tryAcquire(String taskId, String workerId, Duration ttl); void renew(String taskId, String workerId, Duration ttl); void release(String taskId, String workerId); }`
  - `interface ControlFlagPort { void requestPause(String taskId, int expectedVersion); void requestCancel(String taskId, int expectedVersion); ControlFlag read(String taskId); int currentVersion(String taskId); void clear(String taskId); }`
  - `interface ProgressPort { void publish(String taskId, int percent, String currentStep); }`
  - `interface TaskRepositoryPort { Optional<TaskInstance> findByTaskId(String taskId); Optional<TaskInstance> findByBizKey(String bizKey); TaskInstance save(TaskInstance t); List<TaskInstance> findDispatchable(Duration stuckAge, int limit); List<TaskInstance> findExpiredLeases(Instant now, int limit); boolean casLease(String taskId, String workerId, Instant expireAt); void updateHeartbeat(String taskId, Instant at); }`
  - `interface TaskStepRepositoryPort { void saveAllIfAbsent(String taskId, List<StepDef> defs); List<TaskStep> findByTaskIdOrderByStepNo(String taskId); void markRunning(String taskId,int stepNo,Instant at); void markDone(String taskId,int stepNo,String checkpoint,Instant at); void markFailed(String taskId,int stepNo,String msg,Instant at); int firstNonDoneStepNo(String taskId); }`
  - `interface TaskEventRepositoryPort { void append(TaskEvent e); List<TaskEvent> findByTaskId(String taskId); }`
  - `interface HumanTaskRepositoryPort { HumanTask save(HumanTask h); Optional<HumanTask> findById(Long id); List<HumanTask> findByTaskId(String taskId); List<HumanTask> findOpenByUser(String claimedOrSubmittedBy); boolean casClaim(Long id, String userId, int expectedLockVersion); void resolve(Long id, String userId, JsonNode formValue, HumanTaskKind kind); }`

- [ ] **Step 1: 写失败测试** — `TaskExceptionConstructionTest` 断言 `new FatalTaskException(ErrorCode.VALIDATION_FAILED,"x").getErrorCode()==VALIDATION_FAILED`、`HumanRequiredException` 携带 kind/title/instruction/formSchema、`PauseSignalException` 是 `RuntimeException` 且为 `ControlSignalException` 子类。
- [ ] **Step 2: 跑确认失败**。
- [ ] **Step 3: 实现上述 record/接口/异常** — 全部放在领域层，不 import 任何 infrastructure/JPA/MQ 类型；JsonNode 用 Jackson（项目已在用）。
- [ ] **Step 4: `mvn -q compile` 通过；测试 PASS；再补 `mvn -q -Dtest=TaskStateMachineTest,ErrorCodeTest,TaskExceptionConstructionTest test` 全绿。
- [ ] **Step 5: Commit** — `feat(task): 新增任务领域模型、Handler 契约与端口定义`。

---

## Task 4: JPA 持久化（4 张任务表）

**Files:**
- Create: `src/main/resources/db/migration/V9__task_framework.sql`
- Create: `infrastructure/task/jpa/{TaskInstanceEntity,TaskStepEntity,TaskEventEntity,HumanTaskEntity}.java`
- Create: 同包 4 个 `*JpaDao`（Spring Data JPA Repository）
- Create: 同包 `JpaTaskRepository,JpaTaskStepRepository,JpaTaskEventRepository,JpaHumanTaskRepository`（端口适配器）
- Test: `src/test/java/com/wikiagent/infrastructure/task/jpa/TaskPersistenceIntegrationTest.java`

**Interfaces:**
- Consumes: Task 3 端口与 record；实体风格对齐 [MetricEventEntity.java](file:///Users/huangyuan/Desktop/AI学习/wiki-Agent/src/main/java/com/wikiagent/infrastructure/persistence/MetricEventEntity.java)（`@Entity/@Table/@Id IDENTITY`、getter/setter，时间用 `Instant`/`LocalDateTime`——本任务统一 `LocalDateTime`，由适配器在 record 边界转换）。
- Produces: 4 个适配器实现，Bean 名即接口名首小写；`@ConditionalOnProperty(wikiagent.task.enabled=true)` 加在一个 `@Configuration` 上统一导入。
- DDL：严格按规格 2.1–2.4 的列与索引，表名/列名下划线风格；`payload/checkpoint/detail/form_schema/form_value` 用 `TEXT`；`task_instance.next_run_at TIMESTAMP NOT NULL`；唯一键 `uk_biz_key`、`uk_task_step`、`uk_task_step_kind` 如规格。

- [ ] **Step 1: 写失败测试** — `TaskPersistenceIntegrationTest`（`@SpringBootTest` 或 `@DataJpaTest` + H2，properties 设 `wikiagent.task.enabled=true`）：① save TaskInstance(PENDING) 后 findByBizKey 命中；② 两条同 bizKey 保存触发唯一键异常；③ `casLease` 并发语义：两条 casLease 只有一条返回 true（用两条 UPDATE 模拟：无条件 owner 时第一条成功、第二条影响 0 行——通过 `@Modifying` UPDATE ... WHERE lease_expire_at IS NULL OR < now 验证）；④ step `saveAllIfAbsent` 重复调用不产生重复行；⑤ event append 两条 findByTaskId 顺序为插入序；⑥ human_task casClaim：lockVersion 0→1 成功一次，再以 expected 0 失败。
- [ ] **Step 2: 跑确认失败** — 表/类不存在。
- [ ] **Step 3: 写 V9 DDL** — 8 张表一次建齐（含 Task 5 的 4 张 handover 表也在同一文件，本步骤先写任务 4 张表的 DDL，Task 5 再向同文件追加 handover 段——不，DDL 需随 Flyway 一次可执行：**本步直接写全 8 张表**，列定义见规格 2.5，避免二次迁移）。
- [ ] **Step 4: 实现实体/Dao/适配器** — 实体与表一一对应；适配器负责 Entity↔record 转换（`TaskInstanceEntity` 的 `payload/error_code` 等列映射）；`casLease`/`casClaim` 用 `@Modifying(flushAutomatically=true) @Query("update ... where ... and lockVersion=:v")` 返回 int。
- [ ] **Step 5: 加装配类** — `infrastructure/task/jpa/TaskJpaConfig.java`（`@Configuration @ConditionalOnProperty(name="wikiagent.task.enabled",havingValue="true") @EnableJpaRepositories(basePackages=..., includeFilters=...)` 或直接 `@ComponentScan`；优先简单方案：各适配器 `@Repository @ConditionalOnProperty` 逐类标注，Dao 由 Spring Data 自动扫描，不加特殊 Config）。
- [ ] **Step 6: 跑测试** — Expected: PASS；并跑 `mvn -q -Dtest=*Test test`（全量）确保 Flyway 在 H2 下可执行、现有测试不破。
- [ ] **Step 7: Commit** — `feat(task): 新增任务框架 V9 建表与 JPA 持久化适配器`。

---

## Task 5: 交接清单 MySQL 化与文件迁移

**Files:**
- Modify: `infrastructure/memory/file/FileHandoverRepository.java`（注解改为 `havingValue="file"`，去掉 matchIfMissing）
- Create: `infrastructure/memory/mysql/{MysqlHandoverRepository,DataRefValueResolver,HandoverFileMigrationRunner}.java`
- Create: 同包 4 个 handover 实体 + 4 个 Dao
- Modify: `application/agent/pero/MemoryHandover.java`（如它是默认 bean，检查其条件注解，避免与 mysql/file 三 bean 冲突）
- Test: `src/test/java/com/wikiagent/infrastructure/memory/mysql/MysqlHandoverRepositoryTest.java`、`DataRefValueResolverTest.java`、`HandoverFileMigrationRunnerTest.java`
- Test 资源：临时目录构造假 `todo.json`

**Interfaces:**
- Consumes: 现有端口 [HandoverRepository](file:///Users/huangyuan/Desktop/AI学习/wiki-Agent/src/main/java/com/wikiagent/domain/memory/HandoverRepository.java)（`init/addExecutedNode/addAbandonedPath/addDataReference/load/refreshFieldIndex`）与 [Handover](file:///Users/huangyuan/Desktop/AI学习/wiki-Agent/src/main/java/com/wikiagent/application/agent/pero/Handover.java)（`init/declarePlan/startNode/completeNode/failNode/abandonPath/persistEvent`）。V9 DDL 的 4 张 handover 表已在 Task 4 建好。
- Produces:
  - `@Service @ConditionalOnProperty(name="wikiagent.memory.handover-adapter",havingValue="mysql",matchIfMissing=true) class MysqlHandoverRepository implements HandoverRepository, Handover`
    - 同一类实现两个端口（现有 MemoryHandover 也是合一的，先 Grep 确认其绑定方式并对齐；若两接口当前由不同 bean 实现，则本类拆两个类共享一个内部 dao 组件）。
    - `load(userId,sessionId)` 返回与 File 版 JSON **同构**的字符串（`{originalRequest,executedNodes,abandonedPaths,dataReferences}`，Jackson ObjectNode 拼装），保证 PromptComposer 消费方零改动。
    - `refreshFieldIndex(...)` 不再调用 Python：调 `DataRefValueResolver` 从事务表与工具结果回填 `handover_data_ref`。
  - `class DataRefValueResolver { Map<String,String> resolve(String checklistJson); String replacePlaceholders(String text, Map<String,String> values); }`：`[DATA:order_id]` 正则 `\[DATA:([A-Za-z0-9_]+)\]` 匹配替换；缺失键保留原占位符不改写。
  - `class HandoverFileMigrationRunner implements ApplicationListener<ApplicationReadyEvent>`：`wikiagent.memory.file-migration.enabled=true` 时扫描 `${wikiagent.memory.handover-dir}/{userId}/{sessionId}/todo.json`，逐文件 upsert 入四张表，迁移结果写日志计数；重复执行幂等（按 user+session 跳过或 upsert）。

- [ ] **Step 1: 写失败测试** — ① MysqlHandoverRepositoryTest：`init` 后 `declarePlan/startNode/completeNode/abandonPath/addDataReference` 序列调用，`load` JSON 断言四段存在且 executedNodes 含 nodeId/description，重复 init 同 user/session 不产生重复 checklist；② DataRefValueResolverTest：`"订单 [DATA:order_id] 金额 [DATA:refund_amount]"` 给两键值后替换正确，缺失键保留；③ MigrationRunnerTest：临时目录放 2 份 todo.json（一份合法、一份损坏 JSON），运行后合法的 2 条入库、损坏的跳过并告警，再跑一遍不重复。
- [ ] **Step 2: 跑确认失败。**
- [ ] **Step 3: 实现实体/Dao** — 4 实体映射 V9 的 handover 四表（checklist 唯一 user+session；node.seq 自增分配；data_ref upsert）。
- [ ] **Step 4: 实现 MysqlHandoverRepository** — 所有写方法包在 `@Transactional` 内；`load` JSON 字段名与 FileHandoverRepository 输出逐字一致（`originalRequest/executedNodes/abandonedPaths/dataReferences`，元素键 `nodeId/description` 与 `key/value`、放弃路径 `nodeId/reason`）。
- [ ] **Step 5: 实现 DataRefValueResolver 与 MigrationRunner**。
- [ ] **Step 6: 改 File 条件注解** — `FileHandoverRepository` 注解改 `havingValue="file"`（删 matchIfMissing）；Grep `MemoryHandover` 的 bean 注解，若其无条件注册则补 `havingValue` 互斥条件，确保 mysql 默认下容器只有一个 Handover/HandoverRepository bean。
- [ ] **Step 7: 跑测试** — 新增 3 个测试 PASS；全量 `mvn -q test` PASS（现有 FileHandoverRepository 相关测试通过 properties 指定 `handover-adapter=file`）。
- [ ] **Step 8: Commit** — `feat(task): 交接清单迁移至 MySQL 并以确定性解析替代 Python 字段索引`。

---

## Task 6: Redis 协调端口与本地降级实现

**Files:**
- Create: `infrastructure/task/redis/TaskRedisConfig.java`
- Create: 同包 `RedisLeasePort/RedisControlFlagPort/RedisProgressPort/RedisTaskStreamBus`
- Create: 同包 `JvmLeasePort/JvmControlFlagPort/JvmProgressPort/InProcessTaskStreamBus`
- Create: `application/task/TaskStreamBus.java`（接口 + 事件 record：`StreamEvent(String taskId,String type,JsonNode payload)`，type ∈ delta/done/error/progress）
- Test: `RedisBackedPortsTest`（用 JVM 实现验证语义；Redis 实现仅在 profile=it 跑，本任务不依赖真 Redis）

**Interfaces:**
- Consumes: Task 3 端口；现有 `StringRedisTemplate`（仅在 `wikiagent.redis.enabled=true` 时存在）、RedissonClient（同理）。
- Produces: 8 个 bean，用 `@ConditionalOnProperty`/`@ConditionalOnBean(StringRedisTemplate.class)` 选择：Redis 可用 → Redis 实现；否则 → JVM 实现。键规范按规格 5.1：`wikiagent:task:lease:{taskId}`（TTL 30s）、`wikiagent:task:control:{taskId}`（Hash flag/version）、`wikiagent:task:progress:{taskId}`（TTL 2h）、stream 频道 `wikiagent:task:stream:{taskId}`（Redis pub/sub；JVM 版用 `ApplicationEventPublisher` + 本地订阅表）。
- `TaskStreamBus`：`void publish(StreamEvent e); AutoCloseable subscribe(String taskId, Consumer<StreamEvent> listener)`（SSE 桥在 Task 12 用；JVM 实现用 ConcurrentHashMap<String,List<Consumer>>，Redis 实现用 MessageListener + 每频道一个监听容器，引用计数注销）。

- [ ] **Step 1: 写失败测试** — 用 JVM 实现：① tryAcquire 成功后第二人 tryAcquire 同 task 返回 false；renew 后过期时间推后（注入 Ticker/可直接测 release 后可再获取）；② ControlFlag：requestPause(version=0) 后 read=PAUSE、currentVersion=1；expectedVersion 不匹配抛 `OptimisticControlConflictException`（新建于 application/task）；clear 后 read=NONE；③ progress publish 被同 taskId 订阅者收到一次、跨 taskId 收不到；④ subscribe 返回的 close 后不再收到。
- [ ] **Step 2: 跑确认失败。**
- [ ] **Step 3: 实现 JVM 四件套 + 接口/异常。**
- [ ] **Step 4: 实现 Redis 四件套** — Lease 用 `redisTemplate.opsForValue().setIfAbsent(key,workerId,ttl)`，renew 用 Lua 校验 owner 后 pexpire；Control Hash 双字段 + version 校验（expectedVersion 不匹配抛同一异常）；Progress 用 Hash + expire；StreamBus 基于 `RedisMessageListenerContainer`（`@ConditionalOnBean` 时才建容器）。
- [ ] **Step 5: 条件装配** — Redis 四件加 `@ConditionalOnBean(StringRedisTemplate.class)`；JVM 四件加 `@ConditionalOnMissingBean(对应接口.class)`；确保 `wikiagent.redis.enabled=false`（当前本地默认）启动时装配 JVM 实现。
- [ ] **Step 6: 验证** — 新测试 PASS；`REDIS_ENABLED=false mvn -q spring-boot:run` 冒烟 10 秒内启动无 bean 冲突后停止（用 `timeout 25 mvn spring-boot:run` 或启动日志断言 "Started WikiAgentApplication"，与项目 STARTUP.txt 的验证方式一致）。
- [ ] **Step 7: Commit** — `feat(task): 新增租约/控制/进度/流桥端口的 Redis 实现与 JVM 降级`。

---

## Task 7: 投递层（RocketMQ + 本地调度）与 outbox 补偿

**Files:**
- Create: `infrastructure/task/mq/{RocketMqConfig,RocketMqTaskDispatcher,RocketMqTaskConsumer,LocalTaskDispatcher,RocketMqHealthIndicator,TaskDlqConsumer}.java`
- Create: `application/task/{DispatchCompensationJob,Backoff}.java`
- Modify: `config/TaskProperties.java`（无需，backoff 已在 Task 1）
- Test: `LocalDispatchRoundTripTest.java`、`BackoffTest.java`、`DispatchCompensationJobTest.java`

**Interfaces:**
- Consumes: `TaskDispatcherPort`、`TaskRepositoryPort`、`TaskHandlerRegistry`（Task 8 建——本任务先定义接口 `String[] supportedTypes()` 与按 type 取并发度，实际 registry 在 Task 8 实现；为避免循环，Consumer 调用的目标抽象成 sink，本任务定义 `interface TaskMessageSink { void onMessage(String taskId,String taskType); void onWatchdog(String taskId,String ownerWorkerId); }`（onWatchdog 的实现在 Task 9 补齐，本任务先在 sink 接口声明，Task 8 的 TaskWorker 实现 onMessage 并对 onWatchdog 给空实现，Task 9 替换为真实逻辑）。投递协议：普通消息只带 taskId/type；看门狗消息附加 `watchdog=true`、`ownerWorkerId`，Consumer 据属性分流到 onWatchdog。
- Produces:
  - RocketMQ：Topic 常量 `TASK_DISPATCH`；Tag `INGEST`/`AGENT`；消费组 `cg-task-ingest`/`cg-task-agent`；`RocketMqConfig` 在 `wikiagent.task.enabled=true && wikiagent.task.mq=rocketmq` 时创建 `DefaultMQProducer`（start/`@PreDestroy` shutdown，nameServer 取 `${ROCKETMQ_NAME_SERVER:127.0.0.1:9876}`）；Consumer 两个 `DefaultMQPushConsumer`（subscribe topic + tag，consumeThreadMin/Max 取 concurrency.ingest/agent），`MessageListenerConcurrently` 内调 `TaskMessageSink.onMessage`，正常 CONSUME_SUCCESS，业务异常 RECONSUME_LATER（仅兜底，主重试走 DB）。
  - `RocketMqTaskDispatcher.dispatch(taskId,type,delayLevel)`：level>0 用 `setDelayTimeLevel`，level=0 立即；发送失败抛异常（由提交方/outbox 处理）。`dispatchWatchdog`：消息加属性 `watchdog=true`、`ownerWorkerId`，延迟等级 4（30s）。
  - `LocalTaskDispatcher`：`mq=local` 时 `ThreadPoolTaskScheduler` 按 type 两个线程池（池大小取 concurrency），`dispatch` 用 `schedule(() -> sink.onMessage(...), Backoff.durationForLevel(delayLevel))`；`dispatchWatchdog` 延迟 leaseTtlSec 后调 `sink.onWatchdog`。
  - `DispatchCompensationJob`：`@Scheduled(fixedDelayString="${wikiagent.task.dispatch-retry-scan-sec:5}000")`：扫 PENDING 且 `next_run_at<=now` 且 enqueue_at 为空或早于 now-5s 的任务，重新 dispatch（attempt 不增加）；同租户 RUNNING 数 ≥ `tenant-max-concurrent`（>0 时）跳过本次投递，等下轮扫描。
  - `Backoff`：`static Integer delayLevelForAttempt(int attempt)` → attempt 1→3(10s)、2→4(30s)、3→6(2m)，越界返回 6；`static Duration durationForAttempt(int attempt)`（10s/30s/2m，越界 2m）；`static Duration durationForLevel(int level)`（3→10s、4→30s、6→2m）。
  - `RocketMqHealthIndicator` 实现 `HealthIndicator`（producer 可用→UP，否则 DOWN），本地模式不注册。

- [ ] **Step 1: 写失败测试** — BackoffTest 断言三级映射；LocalDispatchRoundTripTest：注册假 sink，dispatch 立即与 delay=3 两次，sink 收到且 taskType/tag 正确（用 Awaitility 或 CountDownLatch + await）；CompensationJobTest：构造 PENDING 且滞留任务 → 运行后 dispatcher 被调 1 次；同租户超限时不调；next_run_at 未来的不调。
- [ ] **Step 2: 跑确认失败。**
- [ ] **Step 3: 实现 Backoff + TaskMessageSink + Local + Job。**
- [ ] **Step 4: 实现 RocketMQ 四件**（Config/Producer/Consumer/Health/DLQ）；DLQ consumer 订阅 `%RETRY%`/DLQ topic 在 RocketMQ 5.x 经典客户端用 `consumer.setConsumeFromWhere` + 订阅 DLQ topic `%DLQ%cg-task-*`，收到只落 `task_event`（FAIL 已由 worker 置位）并 log.error；若 DLQ API 不稳定，允许仅注册两个 push consumer 的默认重试上限（`setMaxReconsumeTimes(0)`）并以日志告警，保证编译与启动，DLQ 监听标记注释清楚（这是唯一允许"接口实现最小化"的点）。
- [ ] **Step 5: 验证** — 单测 PASS；`mvn -q -DskipTests package` 成功；本地 `mq=local` 启动不创建任何 RocketMQ bean（断言启动日志无 DefaultMQProducer）。
- [ ] **Step 6: Commit** — `feat(task): 接入 RocketMQ 投递并提供本地调度降级与 outbox 补偿`。

---

## Task 8: TaskWorker 核心执行器

**Files:**
- Create: `application/task/{TaskWorker,TaskHandlerRegistry,ErrorClassifier,TaskControlContext}.java`
- Test: `src/test/java/com/wikiagent/application/task/TaskWorkerTest.java`、`ErrorClassifierTest.java`

**Interfaces:**
- Consumes: 全部端口、TaskHandler、TaskStateMachine、Backoff、ErrorClassifier；`TaskWorker implements TaskMessageSink`。
- Produces:
  - `class TaskHandlerRegistry { void register(TaskHandler h); Optional<TaskHandler> handler(String type); String[] supportedTypes(); }`（启动时注入 `List<TaskHandler>` 自动收集）。
  - `class ErrorClassifier { record Decision(ErrorCode code,boolean retryable){} Decision classify(Throwable t); }`：`FatalTaskException`→其 code 不重试；`HumanRequiredException` 不分类（worker 单独处理）；`SocketTimeoutException`/Spring `ResourceAccessException`→TIMEOUT；HTTP 5xx 消息/状态→THIRD_PARTY_5XX；4xx→THIRD_PARTY_4XX；`IllegalArgumentException`/`ParseException`(PDFBox)→VALIDATION_FAILED/PARSE_FAILED（含"不支持"/"加密"/"损坏"关键字→PARSE_FAILED）；其余 INTERNAL 可重试。
  - `TaskWorker.onMessage(taskId,type)` 流程严格按规格 3.1：状态护栏（非 PENDING/DISPATCH 直接返回）→ `casLease`（失败返回）→ 置 RUNNING+LEASE 事件 → 心跳调度（ScheduledExecutorService，间隔 heartbeatSec，续租 + updateHeartbeat + progress 发布）→ `planSteps` 落库（已存在跳过）→ 从 `firstNonDoneStepNo` 起逐步：设置 `TaskControlContext`（ThreadLocal，持有 taskId 与端口，供 ReActExecutor 调用，本任务先建 `application/task/TaskControlContext.java` 静态 set/get/clear 与 `checkpointAndThrowIfSignaled()`）→ 每步先查控制标志，**以 MySQL `task_instance.control_version` 为权威复核**：先读 ControlFlagPort（Redis 快通道），再读 DB 任务记录的 controlVersion/suspendReason，任一来源为 PAUSE/CANCEL 即生效（Redis 丢键不导致指令失效）；CANCEL→onCancel+CANCELLED+事件+返回；PAUSE→SUSPENDED+事件+返回，不推进步骤→ step RUNNING+STEP_START → Future+超时执行 `executeStep`（步骤线程池，timeoutSec 取值优先级 StepDef>默认，AGENT 用 agentStepTimeoutSec）→ 成功落 DONE/checkpoint/progress；抛 `HumanRequiredException`→建 human_task(OPEN)+WAIT_HUMAN+返回；抛其余→ErrorClassifier 决策，可重试且 attempt<max → RETRY 事件+attempt+1+next_run_at=now+backoff+duration+dispatch(delayLevel)+PENDING；否则 FAIL+FAILED。
  - 全部步骤 DONE → COMPLETED+resultRef+COMPLETE 事件；finally 清心跳线程、清 ThreadLocal、release 租约（仅 owner==自己时）。

- [ ] **Step 1: 写失败测试（用 fake handler + JVM 端口 + H2）**，逐例：① happy path：3 步 handler 跑完 status=COMPLETED、progress=100、事件序列含 SUBMIT(由提交服务造，本测试直接造 PENDING 实例)/LEASE/STEP_START×3/COMPLETE；② 重复消息：RUNNING 中再次 onMessage 直接幂等返回，handler 执行次数不增；③ 可重试：第 1 步抛 RetryableTaskException(TIMEOUT) 两次后成功 → attempt 记录正确、最终 COMPLETED、第一步实际执行 3 次而后续步骤 1 次；④ 致命错误：Fatal(VALIDATION_FAILED) → FAILED、error_code 正确、不再 dispatch；⑤ 暂停：第 1 步执行前置 PAUSE → SUSPENDED；再 onMessage（模拟 resume 后投递）且标志清除 → 继续完成；⑥ 取消：置 CANCEL → onCancel 被调用一次、CANCELLED；⑦ HumanRequired → WAITING_HUMAN + human_task OPEN；⑧ 步骤超时：handler sleep 超过测试用 200ms 超时 → 按可重试处理；⑨ 断点续跑：预置 step0=DONE 的 task，onMessage 从 step1 开始，step0 的执行计数为 0；⑩ 脏 checkpoint：预置 step1.checkpoint="{坏json"，onMessage 一次即 FAILED(INTERNAL)、不再 dispatch（不得 NPE/死循环）；⑪ Redis 标志丢失：ControlFlagPort 桩恒返回 NONE，但 DB control_version=1 且 suspend_reason 非空 → 仍 SUSPENDED。
另在 `ErrorClassifierTest` 中覆盖 7 类 error_code 映射：SocketTimeout→TIMEOUT(true)、消息含 "503" 的异常→THIRD_PARTY_5XX(true)、含 "400"→THIRD_PARTY_4XX(false)、Fatal(BUDGET_EXCEEDED)→false、PDFBox 异常消息含"损坏"→PARSE_FAILED(true)、普通 RuntimeException→INTERNAL(true)。
- [ ] **Step 2: 跑确认失败。**
- [ ] **Step 3: 实现 ErrorClassifier 与单测先行的分类分支**（可在本任务内先补 ErrorClassifierTest 覆盖 7 类映射关键字）。
- [ ] **Step 4: 实现 TaskControlContext（ThreadLocal）与 Registry。**
- [ ] **Step 5: 实现 TaskWorker**（不含人工 resolve 驱动——resolve 后重新 dispatch 由 Task 9/10 的服务完成；本任务 worker 只需识别 WAITING_HUMAN 状态消息直接跳过）。
- [ ] **Step 6: 跑全部 TaskWorkerTest 用例 PASS；全量 `mvn -q test` PASS。**
- [ ] **Step 7: Commit** — `feat(task): 实现任务执行器：租约/心跳/步骤循环/超时/重试/控制响应`。

---

## Task 9: 恢复扫描、重放与提交服务

**Files:**
- Create: `application/task/{TaskRecoveryJob,TaskWatchdog,TaskSubmissionService,TaskIds}.java`
- Modify: `application/task/TaskWorker.java`（领取成功后发看门狗延迟消息）
- Modify: RocketMQ 消费入口支持看门狗消息（属性 `watchdog=true`），LocalTaskDispatcher 同协议
- Test: `TaskRecoveryJobTest.java`、`TaskSubmissionServiceTest.java`、`TaskWatchdogTest.java`

**Interfaces:**
- Consumes: 全部端口、TaskStateMachine、TaskDispatcherPort、Backoff。
- Produces:
  - `TaskSubmissionService.submit(TaskPayload p)`：生成 TaskIds（`tsk_yyyyMMddHHmmss_`+6 位随机）；bizKey 缺省时用 `{taskType}:{idempotencyKey 或 UUID}`；findByBizKey 命中则直接返回既有 taskId（不抛异常，由控制器层标注 duplicate）；否则建 TaskInstance(PENDING,next_run_at=now,maxAttempts 取 payload 或默认)+SUBMIT 事件+落库+立即 dispatch(level 0)；dispatch 抛异常不外抛（outbox 补偿兜底），任务保持 PENDING。
  - `TaskSubmissionService.replay(taskId, actorId)`：仅终态可重放 → REPLAY 事件（actor=USER）+ attempt=0 + 清错误字段 + PENDING + dispatch（Task 10 控制器注入此方法）。
  - `TaskRecoveryJob`：`@Scheduled(fixedDelayString="${wikiagent.task.recovery-scan-sec:15}000")`：`findExpiredLeases(now,limit)` → 复用统一回收方法 `reclaim(task, reason)`：attempt+1 ≤ max 则 RETRY 事件 + PENDING + dispatch(Backoff.delayLevelForAttempt(attempt))；超过则 FAIL+FAILED+告警日志。
  - `TaskWatchdog`：规格 §1.4 看门狗。Worker 领取成功时额外 `dispatch` 一条延迟 = leaseTtl 的看门狗消息（RocketMQ 用最接近 30s 的延迟等级 4=30s；Local 用 Duration），消息带属性/参数 `watchdog=true` 与 `ownerWorkerId`；`TaskWatchdog.onWatchdog(taskId, ownerWorkerId)` 核对当前任务：仍 RUNNING 且 `lease_owner==ownerWorkerId` 且 `heartbeat_at` 早于 now-leaseTtl → 调同一个 `reclaim`（防止 recovery 扫描漏判的双保险）；心跳正常或 owner 已变 → 无动作。复用 TaskMessageSink 或给 sink 加 `onWatchdog` 二选一，采用后者：`interface TaskMessageSink { void onMessage(String taskId,String taskType); void onWatchdog(String taskId,String ownerWorkerId); }`（同步修改 Task 7 的接口与两处实现）。

- [ ] **Step 1: 写失败测试** — RecoveryJob：① RUNNING 且 lease 过期、attempt=1 → 运行后 PENDING、attempt=2、dispatcher 收到 level=4(30s)；② attempt 已达 max → FAILED + FAIL 事件；③ lease 未过期的 RUNNING 不动。Watchdog：① 心跳停更且 owner 匹配 → 触发 reclaim；② heartbeat 新鲜 → 无动作；③ owner 已变（任务被他人重新领取）→ 无动作。Submission：① 正常提交返回 tsk_ 前缀 id 且 PENDING 并 dispatch 一次；② 同 bizKey 第二次返回相同 id、dispatcher 不多发；③ dispatcher 抛异常时提交仍成功、任务 PENDING；④ 无 bizKey 无 idempotencyKey 时自动生成且不与他人冲突；⑤ TaskId 连续生成 1000 个无重复；⑥ replay FAILED 任务 → PENDING、attempt=0、REPLAY 事件。
- [ ] **Step 2: 跑确认失败。**
- [ ] **Step 3: 实现 TaskIds/TaskSubmissionService。**
- [ ] **Step 4: 实现 TaskRecoveryJob + 抽取共享 reclaim；检查 `@EnableScheduling` 是否已开启（Grep AsyncConfig/主类，已有则不动）。**
- [ ] **Step 5: 实现 TaskWatchdog，扩展 TaskMessageSink，Worker 领取后发看门狗消息，RocketMQ/Local 两投递器与两消费者支持该消息类型。**
- [ ] **Step 6: 验证** — 新测试 PASS；全量 `mvn -q test` PASS。
- [ ] **Step 7: Commit** — `feat(task): 新增任务提交幂等、崩溃恢复扫描、看门狗与失败重放`。

## Task 10: 人工接管、控制服务与 REST API

**Files:**
- Create: `application/task/{TaskControlService,HumanTaskService,TaskQueryService}.java`
- Create: `interfaces/task/dto/{SubmitTaskRequest,TaskView,StepView,EventView,HumanTaskView,SuspendRequest,ClaimRequest,ResolveTaskRequest}.java`
- Create: `interfaces/task/{TaskController,HumanTaskController}.java`
- Modify: `web/GlobalExceptionHandler.java`（加 409/400 映射：重复提交、乐观冲突、第二人接管、503 开关关闭）
- Test: `src/test/java/com/wikiagent/interfaces/task/TaskApiIntegrationTest.java`

**Interfaces:**
- Consumes: Task 8/9 服务与端口。
- Produces: HTTP 契约严格按规格 4.2：
  - `POST /api/tasks`（body：taskType/bizKey 可选/args；头 `Idempotency-Key`、`X-User-Id`；`wikiagent.task.enabled=false` → 503）→ 200 `{taskId,duplicate:false}`；重复 → 200 `duplicate:true`（幂等语义用 200 + 标志，不新建；控制器也可给 409——按规格"返回首个 taskId 与重复标志"，采用 200+duplicate）。
  - GET `/api/tasks/{id}`、`/steps`、`/events`、`/human-tasks`；GET `/api/tasks?status=&mine=`。
  - POST `/api/tasks/{id}/suspend|resume|cancel`（body `{expectedVersion}` 可空，空表示不校验）；resume：SUSPENDED→PENDING+RESUME 事件+dispatch；suspend/cancel 走 ControlFlagPort 双写 + controlVersion+1 + REQUEST_CANCEL/SUSPEND 事件。
  - `POST /api/human-tasks/{id}/claim`（头 X-User-Id）：casClaim 成功→CLAIMED+HUMAN_TAKE 事件；第二人 409。
  - `POST /api/human-tasks/{id}/resolve` body `{kind,formValue}`：INPUT → HUMAN_RESOLVE 事件+form_value 落库+任务 PENDING（payload args 合并 humanInputs）+dispatch；DIRECT_RESOLVE → 任务 COMPLETED+resultRef 可在 body 传。
  - `POST /api/tasks/{id}/replay`。

- [ ] **Step 1: 写失败测试（`@SpringBootTest(webEnvironment=RANDOM_PORT)`，mq=local、enabled=true、H2）**：提交→查询 PENDING→（用可控 fake handler 制造 WAITING_HUMAN 或直接插库）claim 200、第二人 claim 409；resolve INPUT 后任务重新入队并最终 COMPLETED；DIRECT_RESOLVE 直接 COMPLETED；suspend/resume 往返；cancel 终态 CANCELLED；replay FAILED 任务回 PENDING；events 按时间序返回；`enabled=false` 时 POST 503；expectedVersion 过期返回 409。
- [ ] **Step 2: 跑确认失败。**
- [ ] **Step 3: 实现三个 Service**（HumanTaskService.claim/resolve 内完成 human_task 状态机 + task_instance 状态迁移 + task_event 追加，均在一个 `@Transactional` 方法内；resolve 的 dispatch 放事务提交后 `TransactionSynchronization.afterCommit`，避免未提交即消费）。
- [ ] **Step 4: 实现 DTO/Controller/异常映射。**
- [ ] **Step 5: 验证** — 集成测试 PASS；手动 curl 冒烟（本地 mq=local）：提交一个 INVALID handler 的 fake 任务类型不可行——用真实 INGEST 的 API 在 Task 11 后验；本任务用测试内 fake handler bean（`@TestConfiguration`）即可。
- [ ] **Step 6: Commit** — `feat(task): 新增任务控制/人工接管服务与任务 REST API`。

---

## Task 11: IngestTaskHandler 与上传链路切换

**Files:**
- Create: `application/task/handler/IngestTaskHandler.java`
- Modify: [controller/DocumentController.java](file:///Users/huangyuan/Desktop/AI学习/wiki-Agent/src/main/java/com/wikiagent/controller/DocumentController.java)（enabled 时走任务提交）
- Modify: [service/ingest/IngestionService.java](file:///Users/huangyuan/Desktop/AI学习/wiki-Agent/src/main/java/com/wikiagent/service/ingest/IngestionService.java)（抽取同步可单步复用的内部方法；保留 `@Async` 旧入口给 enabled=false）
- Test: `IngestTaskHandlerTest.java`、`UploadAsyncFlowIntegrationTest.java`
- Test 资源：`src/test/resources/task-samples/legacy.doc`（伪 .doc 字节，触发"不支持旧版二进制"）；PDF 与 xlsx 样本由测试代码用 PDFBox（`PDDocument`+`PDPage`+`PDPageContentStream` 写一行文字）与 POI（`XSSFWorkbook` 写一行带表头数据）在内存生成，对应规格 §7 的三个固定回归样本（普通 PDF / 含表格 xlsx / 不支持 .doc）。

**Interfaces:**
- Consumes: TaskHandler；现有 IngestionService 的 parser/cleaner/splitter/embedding/milvus 协作链。
- Produces:
  - `IngestTaskHandler.taskType()="INGEST"`；`planSteps` 返回 6 步：DOWNLOAD/PARSE/CLEAN/SPLIT/EMBED_AND_PERSIST/INDEX_VERIFY。
  - payload args：`docId,filename,domain,subDomain,identity`；文件字节不入库不进 MQ——DOWNLOAD 步骤从现有 `data/uploads/{docId}/{filename}` 读取（与 [DocumentController](file:///Users/huangyuan/Desktop/AI学习/wiki-Agent/src/main/java/com/wikiagent/controller/DocumentController.java#L75-L79) 落盘位置一致）。
  - bizKey：`ingest:{sha256(fileBytes)}:{userId}`，在控制器算好传入。
  - 重构 IngestionService：抽 `public IngestOutcome runPipeline(String docId,String filename,byte[],KnowledgeTagContext)`（去掉 @Async 与吞异常的 catch，异常上抛给 handler 分类；状态更新保留），旧 `@Async ingest(...)` 内部调 runPipeline 并 try/catch 维持现状行为。EMBED_AND_PERSIST 幂等：写 Milvus 前以 `docId` 查重（childRepo.countByDocId/docRepo 状态=READY 则跳过重复写入直接返回）。
  - PARSE 步骤异常映射：`.doc` 抛 `FatalTaskException(VALIDATION_FAILED, 原消息)`；PDFBox "encrypt"/"损坏" → `Fatal(PARSE_FAILED)`（规格回归样本只要求 .doc=VALIDATION_FAILED）。
  - Controller：`ObjectProvider<TaskSubmissionService>` 注入（与 PERO bean 互斥同款手法）；enabled 有 bean 时：保存文件+建 KbDocument(状态新增不改，沿用 PARSING)→submit INGEST，返回体加 `taskId`；无 bean 时维持现有 `ingestionService.ingest(...)` 同步异步调用。

- [ ] **Step 1: 写失败测试** — ① HandlerTest（协作者可用真实 parser/cleaner/splitter + H2；EmbeddingModel/MilvusStoreService 用 Mockito mock，避免测试依赖外部服务）：内存生成的普通 PDF 与含表格 xlsx 两个样本均 6 步 DONE、doc 最终 READY；重跑（预置前 3 步 DONE）不重复切分写库（child 行数不翻倍）；`legacy.doc` → Fatal VALIDATION_FAILED（三样本断言 error_code 分别为 null/null/VALIDATION_FAILED）。② UploadAsyncFlowIntegrationTest：`POST /api/documents`（enabled=true，PDF 样本）→ 响应含 taskId；轮询 `GET /api/tasks/{taskId}` 至 COMPLETED；再次上传相同字节 → duplicate=true 且同一 taskId。
- [ ] **Step 2: 跑确认失败。**
- [ ] **Step 3: 重构 IngestionService.runPipeline。**
- [ ] **Step 4: 实现 IngestTaskHandler（含 sha256 工具放 TaskIds 同包或 handler 内私有方法）。**
- [ ] **Step 5: 改 DocumentController 双路径。**
- [ ] **Step 6: 验证** — 测试 PASS；`enabled=false` 下现有 DocumentController 测试/手工上传行为不变（全量 mvn test）。
- [ ] **Step 7: Commit** — `feat(task): 文档入库接入任务框架并支持幂等提交与断点重跑`。

---

## Task 12: AgentTaskHandler 与 PERO 控制挂钩

**Files:**
- Modify: `application/agent/pero/PeroAgent.java`（抽 `executeLoop` 钩子，见下）
- Modify: `application/agent/pero/ReActExecutor.java`（循环顶部加一行控制检查）
- Create: `application/task/handler/AgentTaskHandler.java`
- Create: `infrastructure/task/stream 占位`——无（stream bus 在 Task 6）
- Create: `interfaces/task/TaskSseBridgeController.java`（`GET /api/tasks/{taskId}/stream` SSE，订阅 TaskStreamBus）
- Modify: 聊天提交入口：Grep 定位 SSE 请求进入 `ChatService` 的位置（[ChatService](file:///Users/huangyuan/Desktop/AI学习/wiki-Agent/src/main/java/com/wikiagent/service/chat/ChatService.java) 四层路由），仅在 PERO 分支（`KnowledgeQaAgent`/`PeroAgent.run` 调用链）外包一层：enabled 时提交 AGENT 任务并把原有 SseEmitter 转到 task stream 订阅
- Test: `AgentTaskControlIntegrationTest.java`、`PeroLoopHooksTest.java`

**Interfaces:**
- Consumes: PERO 组件（PeroPlanner/ReActExecutor/Reflector/Optimizer/Generator/EpisodicMemory/Handover）、TaskHandler、TaskStreamBus、ControlFlagPort。
- Produces:
  - PERO 最小重构（不改变 enabled=false 路径行为）：
    - 新增 `public interface PeroLoopHook { default void beforeNode(PlanStep step,int index){} default void afterReactIteration(PlanStep step,int iter){} default Plan remainingPlanForResume(){return null;} }` 与 `PeroLoopHook.NOOP`。
    - `PeroAgent.run(...)` 末尾原 while 循环体抽成 `void executeLoop(Perception ctx, Plan plan, Handover handover, SseSender sse, PeroLoopHook hook)`；节点循环顶部调 `hook.beforeNode`；`ReActExecutor.execute(...)` 增加重载 `execute(step,ctx,handover,maxIter,Runnable iterationGate)`，原方法委托传 `() -> {}`；`PeroAgent` 传 `() -> hook.afterReactIteration(step,i)`（ReActExecutor 在 for 循环顶部执行 gate）。
    - gate 抛 `ControlSignalException` 时 PERO 的 catch 链不得吞掉：在节点 try 内 `catch (ControlSignalException c){ throw c; }` 先于通用 catch。
  - `AgentTaskHandler.taskType()="AGENT"`；args：`userId,sessionId,userInput`；bizKey=`agent:{userId}:{sessionId}:{sha256(userInput)}`。
  - 步骤语义（A 阶段冻结）：`PLAN`（含 perceive+planner）→ 每个 PlanStep 动态 `registerStep(NODE_{i})` → `GENERATE`；**暂停/取消在节点开始前与每次 ReAct 迭代顶部生效（≤1 个 ReAct 步长，约 3s 内）**；中断恢复时重跑被中断的 NODE（completed NODE 不重跑），checkpoint 存剩余 plan 的 JSON（由 AgentTaskHandler 在 PLAN 后自行持有 Plan 副本——通过 hook.beforeNode 拿到 index 从副本切片，不依赖 PERO 内部可变 list）。
  - SSE：handler 内构造 `SseSender` 实现，把 `delta/done` 转发 `TaskStreamBus.publish`（type=delta/done/error）；`TaskSseBridgeController` 订阅 bus 写给真 emitter；前端/现有聊天客户端不改协议。
  - 聊天入口改造方式：`ObjectProvider<TaskSubmissionService>` 存在且当前路由目标为 PERO 时，改为 submit AGENT + 桥接 SSE；其余三层路由（multi-agent 非 knowledge_qa、v1v2、agentRag、legacy）不动。若改造点无法干净切分（PERO 经 IntentAgents 内部调用），允许在 `IntentAgents.KnowledgeQaAgent.handle` 处做分支：注入 `ObjectProvider<AgentTaskLauncher>`，有则 launcher.launchAndBridge(userId,sessionId,input,emitter)，无则原 peroAgent.run。`AgentTaskLauncher` 是本任务新建的薄编排（application/task/handler）。

- [ ] **Step 1: 写失败测试** — ① PeroLoopHooksTest：用 stub ChatModel/ToolExecutor（Mockito）跑 PeroAgent.executeLoop，hook.beforeNode 被调用次数=节点数；gate 在第 2 次迭代抛 PauseSignalException 时异常原样穿出（不被 failNode 吞掉）。② AgentTaskControlIntegrationTest（mq=local，LLM 全部 stub：提供测试配置的 ChatModel bean 返回固定 plan JSON 与 FINAL）：提交 AGENT → RUNNING 后 suspend → 状态 SUSPENDED、steps 已记录；resume → COMPLETED；SSE 桥收到 delta 与 done；cancel 路径 CANCELLED 且不产生最终答案。
- [ ] **Step 2: 跑确认失败。**
- [ ] **Step 3: PERO 重构（抽 executeLoop + 重载 gate + 异常穿透）**，保证现有 PERO 相关测试全绿。
- [ ] **Step 4: 实现 AgentTaskHandler + AgentTaskLauncher + bus SseSender + TaskSseBridgeController。**
- [ ] **Step 5: 聊天入口接入 KnowledgeQaAgent 分支。**
- [ ] **Step 6: 验证** — 新测试 PASS；全量 `mvn -q test` PASS；本地冒烟：`mvn spring-boot:run`（mq=local、无 key 降级模型）下聊天接口不报 500，任务表出现 AGENT 记录（用 H2 控制台或日志确认）。
- [ ] **Step 7: Commit** — `feat(task): Agent PERO 链路接入任务框架，支持步骤级暂停取消与流式桥接`。

---

## Task 13: 端到端验收、Testcontainers CI 与文档收尾

**Files:**
- Create: `src/test/java/com/wikiagent/task/TaskFrameworkE2eIT.java`（Testcontainers MySQL+RocketMQ）
- Create: `.github/workflows/ci.yml`（如仓库无 CI；J 子项目会扩充，本任务只加最小 pipeline：mvn verify + Testcontainers）
- Modify: `docs/部署指南.md`（追加 RocketMQ 与任务框架环境变量一节）
- Modify: `README.md`（特性表追加任务框架一行 + 环境变量表两行）
- Modify: `pom.xml`（failsafe 插件：`*IT` 走 `mvn verify`，surefire 排除 IT；`light` profile 仅跑 surefire）

**Interfaces:** 无新代码接口。

- [ ] **Step 1: 写 E2E IT** — Testcontainers 起 `mysql:8.0`、`redis:7-alpine`、`apache/rocketmq:5.3.1`（nameserver+broker 用 `GenericContainer` 加 waitStrategy 日志 "boot successful"）；用 `@DynamicPropertySource` 注入 MYSQL_URL/REDIS/TASK_MQ=rocketmq/ROCKETMQ_NAME_SERVER/enabled=true。场景：① INGEST txt 上传 → COMPLETED → 可检索（milvus 不起，断言 doc READY + child 行数即可）；② 重复提交同文件 duplicate；③ AGENT stub 任务暂停/恢复；④ 杀掉 worker 语义（不续租：直接把 lease_expire_at 改成过去 + 调 recoveryJob）→ 断点续跑 COMPLETED。
- [ ] **Step 2: 配 failsafe/light profile** — `mvn -q test` 不跑 IT；`mvn -q verify` 跑 IT；无 Docker 环境 `mvn -q test -Dtest.profile=light` 等价全单测。
- [ ] **Step 3: 本地验证** — 能访问 Docker 时跑 `mvn -q verify`（至少 IT 在本机过一次）；不能则在计划执行汇报中明确标注 IT 未本地验证及原因。
- [ ] **Step 4: 最小 CI** — ci.yml：JDK 21、services 可选（直接用 Testcontainers 自起）、`mvn -B verify`；无 Docker runner 时回退 `mvn -B test`（用 matrix 或 continue-on-error 需注释说明，优先只保留 verify 一步）。
- [ ] **Step 5: 文档** — 部署指南追加：RocketMQ 容器、`TASK_MQ/ROCKETMQ_NAME_SERVER/WIKIAGENT_TASK_ENABLED/WIKIAGENT_MEMORY_HANDOVER_ADAPTER/WIKIAGENT_MEMORY_FILE_MIGRATION_ENABLED` 五个变量、降级行为表；README 追加一行特性与变量表两行，不重写章节。
- [ ] **Step 6: 全量验收（对照规格第 8 节 7 条逐项记录结果到 commit message 与最终汇报）**：`mvn -q test` 全绿；手工/自动演示幂等、60s 内恢复（recovery 15s 扫描 + 30s 租约，断言 ≤60s）、暂停 3s 内生效、两种接管、replay、无 todo.json 新写入。
- [ ] **Step 7: Commit** — `test(task): 补充任务框架端到端集成测试、CI 流水线与部署文档`。

---

## Review Focus

1. **MQ 重复/乱序投递下的副作用去重**：同 taskId 消息被投递两次（RocketMQ rebalance、重试）只允许副作用一次——Task 8 测试②与 Task 11 的 docId 幂等查重共同钉住；INGEST 的 EMBED 步必须以 doc 状态/child 行数短路。
2. **暂停在"非检查点时刻"到达**：PAUSE 于步骤执行中间落库，worker 不得杀死进行中的动作，必须且只能在下一个边界生效；恢复后该步骤重跑——Task 8 测试⑤/⑨与 Task 12 测试②覆盖；需额外断言"暂停后 1 次边界检查内生效且 handler 当前调用正常返回而非被 interrupt"。
3. **Redis 中控制标志丢失**：Redis flush 后 PAUSE/CANCEL 不得静默失效——worker 读 Redis 后必须以 MySQL `control_version` 复核（Task 8 测试⑤补一例：清空 ControlFlagPort 的 Redis 模拟——JVM 测试中直接换一个"总是返回 NONE 但 DB version=1"的桩，要求仍走到 SUSPENDED；实现上 Task 8 worker 每边界同时比对 DB control_version）。
4. **checkpoint JSON 过大/损坏**：Milvus 写入等长 checkpoint 或历史脏数据导致反序列化失败时，任务必须 FAILED(INTERNAL) 并给出可 replay 的明确错误，不得死循环重试——Task 8 补一例：预置坏 checkpoint 的 step，onMessage → FAILED 而非 NPE/无限重试。
5. **AGENT 任务恢复时 LLM 计划不稳定**：resume 触发重新规划会破坏"已完成节点不重跑"——Task 12 冻结语义为从 handler 持有的 plan 副本切片恢复，测试②断言完成过的 NODE 在恢复后执行计数为 0；若 planner 被再次调用则测试失败。
