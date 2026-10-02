# 子项目 A：任务与数据链基座 — 设计规格

> 状态：设计已分节确认，待书面规格审阅
> 日期：2026-10-01
> 需求来源：`docs/1001-需求.txt` 第 1、16 条
> 代码基线：Spring Boot 3.5.16 + Java 21 + Spring AI 1.1.2 + spring-ai-alibaba 1.1.2.0 + Milvus 2.5.17 + MySQL/Flyway + Redis/Redisson + 双应用副本部署

---

## 0. 背景与总体路线

`1001-需求.txt` 共 16 条需求，经拆分形成 A–J 共 10 个子项目，**全部都要交付**，按依赖顺序各走一轮「规格 → 计划 → 实施 → 验证」：

| 编号 | 子项目 | 覆盖需求 | 状态 |
|---|---|---|---|
| **A** | **任务与数据链基座** | 1、16 | 本规格 |
| B | 文档智能解析流水线（OCR/语音/版面/表格/结构化提取） | 2、11 | 未启动 |
| C | 数据血缘与版本（原文→页码→模型结果→人工修改可追溯） | 3 | 未启动 |
| D | 确定性规则引擎 + 人工复核留痕 | 6、14 | 未启动 |
| E | RAG/模型治理增强（Prompt 版本、缓存、成本、供应商切换） | 4、12 | 未启动 |
| F | Agent 治理增强（费用上限、工具授权、暂停恢复接管） | 5、13 | 未启动 |
| G | React 前端重做（上传/进度/结果/来源/修改/历史） | 7 | 未启动 |
| H | 测试与模型评测体系（单测/E2E/黄金样本/7 项指标） | 8、15 | 未启动 |
| I | 全链路可观测与韧性（积压监控、限流降级） | 9 | 未启动 |
| J | 运维与安全（CI/CD、灰度、功能开关、备份回滚） | 10 | 未启动 |

依赖关系：A 是 B、E、F、I、J 的共同地基；H 从 A 起同步铺设测试底座。需求 12–15 是对 2/4/5/6/8 的强调，已分别并入对应子项目。

### 0.1 子项目 A 的目标

1. 建设通用任务框架，支持**排队、并发控制、重复提交幂等、超时、重试、取消、崩溃恢复、人工接管**；
2. 首批接入两条真实流程：**文档入库**与 **Agent（PERO）任务**；
3. 将交接清单从本地 `todo.json` 文件迁移到 **MySQL + Redis**（需求 16），废弃 Python 字段索引脚本。

### 0.2 已确认的关键决策

| 决策点 | 结论 |
|---|---|
| 队列中间件 | **RocketMQ**（NameServer + Broker；延迟消息退避、消费组、死信） |
| 架构方案 | **方案一：自研任务框架** —— MySQL 步骤级状态机为真相源，RocketMQ 只投递，Redis 只协调 |
| 首批接入 | 文档入库（INGEST）+ Agent 任务（AGENT） |
| 人工接管 | 两种都支持：① 暂停等人工输入后自动恢复；② 人工直接终结剩余步骤；含接管锁与全程留痕 |

---

## 1. 总体架构

### 1.1 分层结构（沿用现有 DDD 四层）

```
interfaces/task/       TaskController（提交/查询/暂停/取消/恢复/人工动作/重放）
                       TaskDlqController（死信查看，可选）
        │
application/task/      TaskSubmissionService   幂等提交（biz_key 唯一）+ outbox
                       TaskDispatcher          投递 RocketMQ（失败由补偿扫描兜底）
                       TaskWorker              MQ 消费 → 领取（租约）→ 执行 Handler
                       TaskControlService      暂停/取消/恢复/人工接管
                       TaskRecoveryJob         定时扫描崩溃任务 + 滞留 PENDING
                       TaskHandlerRegistry     taskType → TaskHandler
        │
domain/task/           TaskInstance / TaskStep / TaskStateMachine（纯领域逻辑）
                       TaskEvent（追加式审计）/ HumanTask（人工接管点）
                       端口：TaskRepository、TaskEventRepository、HumanTaskRepository、
                            LeasePort、ControlFlagPort、TaskDispatcherPort
        │
infrastructure/task/
   jpa/                JPA 实体与仓储实现（5 张表）
   mq/                 RocketMQ 生产者/消费者、Topic/Tag/消费组/死信配置、本地降级调度器
   redis/              Redis 租约、控制标志、接管锁、进度 Hash
   handover/           MysqlHandoverRepository（替代 FileHandoverRepository）

application/task/handler/
                       IngestTaskHandler（包装现有 IngestionService）
                       AgentTaskHandler（包装 PERO 主循环）
```

核心原则：**任务真相以 MySQL 状态机为准，RocketMQ 只负责投递，Redis 只做协调（租约/锁/即时标志）**。任一层故障均可凭 MySQL 状态核对恢复，不丢任务。

### 1.2 任务级状态机

状态枚举：`PENDING / DISPATCH / RUNNING / SUSPENDED / WAITING_HUMAN / CANCELING / COMPLETED / FAILED / CANCELLED`。

合法迁移：

| 当前状态 | 事件 | 目标状态 |
|---|---|---|
| （新建） | SUBMIT | PENDING |
| PENDING | DISPATCH（消息投递） | DISPATCH |
| PENDING / DISPATCH | LEASE（Worker 领取） | RUNNING |
| RUNNING | 步骤边界检查到 PAUSE | SUSPENDED |
| SUSPENDED | RESUME | PENDING（重新投递） |
| RUNNING | 到达 HumanCheckpoint | WAITING_HUMAN |
| WAITING_HUMAN | 人工 INPUT 恢复 | PENDING（payload 合并后重新投递） |
| WAITING_HUMAN | 人工 DIRECT_RESOLVE | COMPLETED |
| RUNNING / SUSPENDED | REQUEST_CANCEL | CANCELING |
| CANCELING | 补偿完成 | CANCELLED（终态） |
| RUNNING | 步骤失败且可重试且未超上限 | PENDING（退避，next_run_at） |
| RUNNING | 全部步骤 DONE | COMPLETED（终态） |
| RUNNING | 不可重试失败 / 重试耗尽 | FAILED（终态） |

约束：

- **暂停/取消不抢占当前动作**：Worker 仅在步骤检查点响应（Agent 在每个 ReAct 步骤边界检查）。转入 SUSPENDED/CANCELING 时消息正常 ACK，状态持久化；恢复时重新投递，不依赖单条消息长持有。
- 终态三个：COMPLETED / FAILED / CANCELLED，其余状态皆可恢复。
- 所有非法迁移由 `TaskStateMachine` 抛 `IllegalStateTransitionException`，不得绕过。

### 1.3 崩溃恢复

- Worker 领取任务时写 `lease_owner`（实例 ID）与 `lease_expire_at = now + 30s`；执行期间每 10s 续租并刷新心跳。
- `TaskRecoveryJob` 每 15s 扫描 `status IN (RUNNING, DISPATCH) 且 lease_expire_at < now` 的任务：追加 RETRY 事件、attempt+1，未超 maxAttempts 则按退避重新入队，超过则置 FAILED 并告警。
- 恢复执行时从最后一个非 DONE 的 `task_step` 继续，已 DONE 步骤不重复执行。

### 1.4 RocketMQ 拓扑

| 元素 | 设计 |
|---|---|
| Topic | `TASK_DISPATCH` 单 Topic，**Tag 区分任务类型**：`INGEST`、`AGENT`，后续子项目新增 Tag |
| 消费组 | 每类任务独立消费组：`cg-task-ingest`、`cg-task-agent`，并发度独立配置 |
| 业务重试 | 消费失败由任务表 `attempt` 控制：失败后按指数退避（10s/30s/2min）发延迟消息重投；达上限置 FAILED |
| 死信 | RocketMQ 默认 DLQ 兜底；`TaskDlqConsumer` 监听死信 → 落库告警；支持 `POST /api/tasks/{taskId}/replay` 人工重放 |
| 一致性 | **outbox 模式**：提交先写 MySQL（PENDING），MQ 发送失败由补偿扫描（PENDING 滞留 > 5s）重发；消费端以任务状态做幂等护栏 |
| 看门狗 | 任务被领取时发延迟消息，到点核对租约/心跳；与 recovery 扫描构成双保险，靠 lease_owner 校验防重复执行 |

---

## 2. 数据模型（MySQL）

Flyway 新增迁移 `src/main/resources/db/migration/V9__task_framework.sql`。

### 2.1 `task_instance`（任务实例，状态机真相源）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK AUTO_INCREMENT | |
| task_id | VARCHAR(40) NOT NULL UNIQUE | 对外业务 ID，格式 `tsk_{yyyyMMddHHmmss}_{随机}` |
| task_type | VARCHAR(32) NOT NULL | INGEST / AGENT，可扩展 |
| biz_key | VARCHAR(128) NOT NULL | 幂等业务键（见 2.6） |
| payload | JSON NOT NULL | 提交参数 |
| status | VARCHAR(16) NOT NULL | 状态机枚举 |
| priority | TINYINT NOT NULL DEFAULT 5 | 预留插队 |
| attempt | INT NOT NULL DEFAULT 0 | 当前尝试次数 |
| max_attempts | INT NOT NULL DEFAULT 3 | 重试上限 |
| progress_percent | INT NOT NULL DEFAULT 0 | 0–100 |
| result_ref | TEXT NULL | 结果引用 |
| error_code | VARCHAR(32) NULL | 失败分类，见 3.3 |
| error_msg | TEXT NULL | 失败信息 |
| idempotency_key | VARCHAR(64) NULL | 请求头 Idempotency-Key |
| submitted_by | VARCHAR(64) NOT NULL | 提交人 |
| tenant_id | VARCHAR(64) NULL | 组织隔离（J 子项目启用，A 预留） |
| enqueue_at | DATETIME NULL | 最近投递时间 |
| lease_owner | VARCHAR(64) NULL | 领取 Worker 实例 ID |
| lease_expire_at | DATETIME NULL | 租约截止 |
| heartbeat_at | DATETIME NULL | 最近心跳 |
| next_run_at | DATETIME NOT NULL | 退避重试时间，扫描排序字段 |
| suspend_reason | VARCHAR(255) NULL | 挂起原因 |
| control_version | INT NOT NULL DEFAULT 0 | 控制指令版本号，防旧指令覆盖 |
| created_at / updated_at | DATETIME NOT NULL | |

索引：`UNIQUE KEY uk_biz_key (biz_key)`、`KEY idx_status_next_run (status, next_run_at)`、`KEY idx_lease (status, lease_expire_at)`、`KEY idx_submitter (submitted_by)`。

### 2.2 `task_step`（步骤级状态）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK AI | |
| task_id | VARCHAR(40) NOT NULL | |
| step_no | INT NOT NULL | 步骤序号，从 0 起 |
| step_type | VARCHAR(48) NOT NULL | DOWNLOAD/PARSE/... 或 PERCEIVE/PLAN/REACT_n |
| step_name | VARCHAR(128) NOT NULL | 展示名 |
| status | VARCHAR(16) NOT NULL | PENDING/RUNNING/DONE/SKIPPED/FAILED |
| checkpoint | JSON NULL | 步骤中间态快照 |
| started_at / ended_at | DATETIME NULL | |
| error_msg | TEXT NULL | |

约束：`UNIQUE KEY uk_task_step (task_id, step_no)`，`KEY idx_task (task_id)`。

### 2.3 `task_event`（追加式事件流，只增不改）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK AI | |
| task_id | VARCHAR(40) NOT NULL | |
| event_type | VARCHAR(32) NOT NULL | SUBMIT/DISPATCH/LEASE/HEARTBEAT/STEP_START/STEP_DONE/SUSPEND/RESUME/REQUEST_CANCEL/CANCEL/HUMAN_TAKE/HUMAN_RESOLVE/RETRY/COMPLETE/FAIL/REPLAY |
| actor_type | VARCHAR(16) NOT NULL | SYSTEM / USER / WORKER |
| actor_id | VARCHAR(64) NULL | 用户 ID 或 workerId |
| detail | JSON NULL | 控制版本、接管人、表单旧值新值等 |
| created_at | DATETIME NOT NULL | |

索引：`KEY idx_task_event (task_id, id)`、`KEY idx_type_time (event_type, created_at)`。

### 2.4 `human_task`（人工接管点）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK AI | |
| task_id | VARCHAR(40) NOT NULL | |
| step_no | INT NOT NULL | |
| kind | VARCHAR(16) NOT NULL | INPUT / DIRECT_RESOLVE |
| title | VARCHAR(128) NOT NULL | |
| instruction | TEXT NULL | 给接管人的说明 |
| form_schema | JSON NULL | 需人工提供的字段定义 |
| form_value | JSON NULL | 人工提交的 payload |
| status | VARCHAR(16) NOT NULL | OPEN/CLAIMED/RESOLVED/EXPIRED |
| claimed_by | VARCHAR(64) NULL | |
| claimed_at / resolved_at | DATETIME NULL | |
| resolved_by | VARCHAR(64) NULL | |
| lock_version | INT NOT NULL DEFAULT 0 | CAS 接管锁 |
| created_at | DATETIME NOT NULL | |

约束：`UNIQUE KEY uk_task_step_kind (task_id, step_no, kind)`、`KEY idx_ht_status (status)`。同一接管点只能一人接管：Redisson 锁 + `lock_version` CAS 双保险，第二人 claim 返回 409。

### 2.5 交接清单四张表（替代 todo.json）

- **`handover_checklist`**：`id BIGINT PK AI`、`user_id VARCHAR(64)`、`session_id VARCHAR(64)`、`original_request TEXT NOT NULL`（不可变）、`plan_json TEXT NULL`（declarePlan 快照）、`status VARCHAR(16)`、`version INT NOT NULL DEFAULT 0`、`created_at/updated_at DATETIME`；`UNIQUE KEY uk_user_session (user_id, session_id)`。
- **`handover_node`**：`id`、`checklist_id BIGINT`、`node_id VARCHAR(64)`、`node_type VARCHAR(32)`、`declared_intent TEXT`、`result TEXT`、`status VARCHAR(16)`（PENDING/COMPLETED/FAILED/ABANDONED）、`trace_id VARCHAR(64)`、`seq INT`、`created_at DATETIME`；`KEY idx_checklist (checklist_id)`。
- **`handover_abandoned_path`**：`id`、`checklist_id BIGINT`、`node_id VARCHAR(64)`、`reason TEXT`、`created_at DATETIME`。
- **`handover_data_ref`**：`id`、`checklist_id BIGINT`、`ref_key VARCHAR(128)`、`ref_value TEXT`、`updated_at DATETIME`；`UNIQUE KEY uk_checklist_key (checklist_id, ref_key)`。

四段结构（originalRequest / executedNodes / abandonedPaths / dataReferences）与现有 `Handover` 接口语义一一对应。

### 2.6 biz_key 幂等键规则

- INGEST：`ingest:{fileSha256}:{userId}`（同一文件同人不重复入库；文件名/标签不同的更新走显式 update，不复用本键）。
- AGENT：`agent:{userId}:{sessionId}:{sha256(用户当轮输入)}`。
- 重复提交（biz_key 冲突）返回首个任务的 taskId，响应标志其为重复请求，不新建任务。

---

## 3. 执行机制

### 3.1 Worker 执行流程

```
RocketMQ 消息到达（tag=INGEST/AGENT）
 ① 幂等护栏：查 task_instance.status，非 PENDING/可重试态 → 直接 ACK 跳过
 ② 抢租约：UPDATE ... SET lease_owner=?, lease_expire_at=now+30s, status=RUNNING
         WHERE task_id=? AND status IN (PENDING,DISPATCH)
           AND (lease_expire_at IS NULL OR lease_expire_at < now)
         影响行数=0（被别的副本抢走）→ ACK 退出；成功 → 记 LEASE 事件
 ③ 心跳：每 10s 续租 lease_expire_at + 刷新 Redis progress Hash
 ④ 载入/初始化 task_step（Handler#planSteps；已存在按 checkpoint 断点续跑）
 ⑤ 逐步执行，每步边界：
    a. 检查控制标志（读 Redis，按 MySQL control_version 校验）
       PAUSE  → SUSPENDED + 事件 → ACK
       CANCEL → CANCELLED + 事件 + Handler#onCancel 补偿 → ACK
    b. step RUNNING → Handler#executeStep(ctx)
    c. 成功 → step DONE + checkpoint 快照 + progress 上报
    d. HumanCheckpoint → human_task(OPEN) + WAITING_HUMAN → ACK 挂起
    e. 异常 → 按 3.3 分类处理
 ⑥ 全部 DONE → COMPLETED + result_ref + COMPLETE 事件 → ACK
```

长任务以"状态持久化 + 重新投递"为主要可靠手段，不靠单条消息持有到底；看门狗只做租约核对，不触发重复执行。

### 3.2 TaskHandler 扩展点契约

```java
public interface TaskHandler {
    String taskType();                                              // "INGEST" / "AGENT"
    List<StepDef> planSteps(TaskPayload payload);                   // 步骤声明，含人工检查点标记
    StepResult executeStep(TaskExecutionContext ctx) throws TaskException;
    default Checkpoint snapshot(int stepNo, TaskExecutionContext ctx);
    default void resume(int stepNo, Checkpoint cp, TaskExecutionContext ctx);
    default void onCancel(TaskExecutionContext ctx);                // 业务补偿
}
```

Handler 只写业务，不直接操作状态机/事件/租约/重试（由 TaskWorker 统一完成）。通过三类异常表达结果：

- `RetryableTaskException(code, message)`：可重试；
- `FatalTaskException(code, message)`：不可重试，直接 FAILED；
- `HumanRequiredException(kind, formSchema, instruction)`：转 WAITING_HUMAN。

**IngestTaskHandler 步骤**：`DOWNLOAD → PARSE → CLEAN → SPLIT → EMBED_AND_PERSIST → INDEX_VERIFY`。PARSE 在 A 阶段仍走现有 `DocumentParser`，但步骤槽位与接口为子项目 B（OCR/语音/版面）预留；向量写入以 `doc_id+chunk_id` 幂等，重跑不产生重复 chunk。

**AgentTaskHandler 步骤**：`PERCEIVE → PLAN → REACT_0..n（每个 PlanStep/ReAct 迭代一个 task_step）→ GENERATE`；暂停/取消检查点置于 ReAct 步骤边界，与现有 `ReActExecutor` 循环对齐；checkpoint 存当前 plan、节点序号、ReAct 轮次；checklist 写入通过 `MysqlHandoverRepository`。

### 3.3 超时、重试与失败分类

| 机制 | 规则 |
|---|---|
| 步骤超时 | 每步可配 timeoutSec，默认 300s；Agent 单步默认 180s；独立调度线程中断并置回待重试 |
| 任务级超时 | payload 可带 deadlineSec，默认 1800s；首次超时按可重试处理，二次仍超时 → FAILED |
| 重试策略 | attempt ≤ maxAttempts（默认 3），指数退避 10s/30s/2min（RocketMQ 延迟消息）；从最后非 DONE 步骤续跑 |
| error_code | `TIMEOUT / THIRD_PARTY_5XX / THIRD_PARTY_4XX / PARSE_FAILED / VALIDATION_FAILED / BUDGET_EXCEEDED / INTERNAL` |
| 不重试即 FAILED | THIRD_PARTY_4XX、VALIDATION_FAILED、BUDGET_EXCEEDED |
| 可重试 | TIMEOUT、THIRD_PARTY_5XX、PARSE_FAILED、INTERNAL |
| 死信与重放 | 重试耗尽 → FAILED + 告警；DLQ 消息落库；`POST /api/tasks/{taskId}/replay` 追加 REPLAY 事件、attempt 清零重新入队 |

---

## 4. 人工接管

### 4.1 两种接管方式

1. **暂停等输入（INPUT）**：任务在检查点挂起（SUSPENDED 或预置 WAITING_HUMAN），接管人 claim 后按 `form_schema` 提交 `form_value`，任务带人工 payload 恢复为 RUNNING（经 PENDING 重新投递），payload 合并进 `TaskExecutionContext`。
2. **人工直接完成（DIRECT_RESOLVE）**：接管人 claim 后直接终结任务，任务转 COMPLETED，记录人工结果引用，Worker 不再执行剩余步骤。

claim/resolve 全部写 `task_event`（actor=USER、actor_id、detail 存表单旧值新值），为子项目 D 的人工复核提供原始留痕。

### 4.2 API 契约

| 方法与路径 | 行为 |
|---|---|
| POST /api/tasks | 提交任务；支持 Idempotency-Key 头/biz_key，重复提交返回首个 taskId |
| GET /api/tasks/{taskId} | 状态、progress_percent、当前步骤、attempt、error |
| GET /api/tasks/{taskId}/steps | 步骤列表与状态/耗时/checkpoint 摘要 |
| GET /api/tasks/{taskId}/events | 事件流（留痕审计） |
| POST /api/tasks/{taskId}/suspend | 暂停；body 带 expectedVersion 乐观控制 |
| POST /api/tasks/{taskId}/resume | 恢复 |
| POST /api/tasks/{taskId}/cancel | 取消 |
| GET /api/tasks/{taskId}/human-tasks | 该任务的人工接管点 |
| POST /api/human-tasks/{id}/claim | 接管；第二人返回 409 |
| POST /api/human-tasks/{id}/resolve | INPUT（恢复）或 DIRECT_RESOLVE（终结） |
| POST /api/tasks/{taskId}/replay | 死信/失败任务人工重放 |
| GET /api/tasks?status=&mine= | 任务列表（排队中/处理中/待我接管/失败） |
| SSE `task-progress` | 任务状态变化 + 百分比 + 当前步骤，复用现有 SSE 机制 |

---

## 5. 交接清单迁移（需求 16）

1. 新增 `MysqlHandoverRepository implements HandoverRepository`，开关 `wikiagent.memory.handover-adapter=mysql`（A 交付后默认值）；`file` 实现保留但不再默认。
2. **废弃** `scripts/extract_field_index.py` 与本地 `field_index.json`：`[DATA:xxx]` 占位符改由确定性 Java 代码从事务表/工具结果解析回填，避免本地文件依赖与 AI 搬运丢值。
3. `AgentTaskHandler` 首次执行时以 `(userId, sessionId)` upsert checklist；任务恢复时从 `handover_node`/`handover_abandoned_path`/`handover_data_ref` 重建交接上下文。
4. checklist 写入与 task_event 追加在**同一本地事务**提交，不引入分布式事务。
5. **一次性数据迁移**：Flyway 仅建表；提供 `HandoverFileMigrationRunner`（`wikiagent.memory.file-migration.enabled=true` 控制，迁移完成后置 false），扫描 `./data/handover/{userId}/{sessionId}/todo.json` 导入四张表，老会话平滑继续。

### 5.1 Redis 协调键

| 键 | 内容 | TTL |
|---|---|---|
| `wikiagent:task:lease:{taskId}` | String = workerId | 30s，每 10s 续 |
| `wikiagent:task:control:{taskId}` | Hash：flag(PAUSE/CANCEL)、version | 随任务生命周期 |
| `wikiagent:humantask:lock:{taskId}:{stepNo}` | Redisson 接管锁 | 接管会话期间 |
| `wikiagent:task:progress:{taskId}` | Hash：percent/step/ts（SSE 用） | 2h |

控制指令双写：MySQL `control_version` 为权威，Redis 为快速读取通道；Worker 读 Redis 后以 MySQL 版本号校验，Redis 丢键回退查库。

---

## 6. 配置、开关与降级

总开关 `wikiagent.task.enabled`，框架 Bean 一律 `@ConditionalOnProperty` 守卫（与 PERO 子系统约定一致）。

| 场景 | 行为 |
|---|---|
| RocketMQ 缺失/不可用 | `TaskDispatcherPort` 降级为 JVM 本地调度器（ThreadPoolTaskScheduler 内存投递）；任务仍落 MySQL，单机可跑通全状态机；健康检查 rocketmq 标 DOWN 并持续告警 |
| Redis 缺失 | 租约/控制标志降级为 MySQL 行字段轮询；接管锁降级为 human_task.lock_version CAS |
| MySQL 缺失 | 不允许降级启动：enabled=true 且无 DataSource 时快速失败并明确提示，防止任务悄悄丢失 |
| enabled=false | 现有同步文档入库与 PERO 直接执行路径保留；任务 API 返回 503 |

```yaml
wikiagent:
  task:
    enabled: true
    mq: rocketmq              # rocketmq | local
    concurrency:
      ingest: 2
      agent: 4
    lease-ttl-sec: 30
    heartbeat-sec: 10
    recovery-scan-sec: 15
    dispatch-retry-scan-sec: 5
    defaults:
      max-attempts: 3
      step-timeout-sec: 300
      agent-step-timeout-sec: 180
      deadline-sec: 1800
    backoff: [10s, 30s, 2m]
  memory:
    handover-adapter: mysql   # file | mysql
    file-migration:
      enabled: true
```

docker-compose 增加 RocketMQ NameServer + Broker（单节点，控制台可选）；`.env.example` 增加 `ROCKETMQ_NAME_SERVER`。

并发保护：同 biz_key 全局只执行一次；同租户并发上限可配（超出保持 PENDING 不投递）。跨用户公平调度与限流列入子项目 I。

---

## 7. 测试策略（H 子项目的第一块底座）

| 层 | 内容 |
|---|---|
| 单元测试 | 状态机全迁移合法性与非法迁移异常、退避计算、biz_key 幂等、7 种 error_code 分类、MysqlHandoverRepository 四段读写、`[DATA:xxx]` 占位符确定性解析 |
| 接口测试 | 提交/重复提交同 taskId、暂停/恢复/取消、claim 第二人 409、INPUT 与 DIRECT_RESOLVE 闭环、replay、列表过滤、乐观锁版本冲突 |
| 任务可靠性 | ① Worker 停止续租 → recovery 约 30s 内回收并断点续跑；② MQ 发送失败 → 滞留 PENDING 被补偿重发；③ 重跑不产生重复 chunk；④ 同 biz_key 并发只执行一次 |
| 降级测试 | mq=local 全流程；Redis 停用走 MySQL 租约；enabled=false 旧链路不受影响 |
| E2E | 文档上传 → COMPLETED → 可检索命中；Agent 提问 → 暂停 → 恢复 → 答案返回且 checklist 在 MySQL 可查 |
| 回归样本 | 固定 3 个样本：普通 PDF、含表格 xlsx、不支持的 .doc（断言 error_code：null/null/VALIDATION_FAILED） |

CI 用 Testcontainers 起 MySQL/Redis/RocketMQ；本地无 Docker 时 `-Dtest.profile=light` 仅跑单元测试。可靠性测试用可控时钟 + 手动触发 recovery job，不依赖真实杀进程。

---

## 8. 验收标准

1. 同一业务键任意并发提交只产生一个任务、副作用只执行一次；
2. 非终态任务在 Worker 崩溃后 60s 内被自动回收，从最后未完成步骤续跑，已完成步骤不重复；
3. 暂停/取消在步骤边界 3s 内生效；两种接管路径闭环，task_event 完整留痕（谁、何时、改了什么）；
4. 退避重试、重试上限、7 类失败分类、DLQ 告警与 replay 均可演示；
5. `handover-adapter=mysql` 默认后不再产生新的 todo.json 写入；历史文件经一次性迁移可在新表查询；Agent 恢复后交接记忆不丢；Python 脚本移除出主链路；
6. RocketMQ/Redis 任一缺失按第 6 节降级，健康检查如实反映；
7. 文档入库与 Agent 两条 E2E 通过；`mvn test` 全绿；Testcontainers 集成测试在 CI 通过。

---

## 9. A 的边界（不做什么）

- 不做 OCR/语音/版面/跨页表格/结构化字段提取（B；PARSE 步骤仅占位）；
- 不做字段到页码/原文片段的四级血缘模型（C）；
- 不做规则版本/回放计算（D）；
- 不做 Agent 费用上限、工具动态授权（F；A 仅提供步骤级暂停/取消/接管底座）；
- 不做 React 前端（G）；A 保证现有原生页面的上传链路切换为异步轮询/SSE 不破，新 API 供 G 使用；
- 不做跨用户限流、成本看板、CI/CD、灰度开关平台（I/J；仅预留配置位与事件数据）。
  注：灰度能力后续以 `GrayReleaseService`（wikiagent.gray.features.*，稳定哈希分桶+名单）落地，
  首个决策点 rerank，见 docs/operations/feature-toggles.md §六。
