# 子项目 A（任务与数据链基座）—— 仍需手工执行事项清单

> 背景：分支 `feature/task-framework`（worktree `.worktrees/task-framework`）已完成 13 个 Task + 10 轮「检查→优化→检查」审计。
> 代码侧事项已全部闭环；以下事项受环境/权限限制，必须由人工执行。

## 1. 合并与 CI 验证（最高优先级）

| # | 事项 | 原因 | 操作 |
|---|---|---|---|
| 1.1 | 推送分支并创建 PR | 本地改动未推送 | `git push -u origin feature/task-framework`，PR 标题建议：`feat(task): 子项目A 任务与数据链基座（13 Task + 审计修复）` |
| 1.2 | 首次 CI 运行盯 `TaskFrameworkE2eIT` | 本机无 Docker 运行时（docker/colima/podman/orbstack 均缺），Testcontainers IT 只能在 CI 验证 | `gh run watch`；最大不确定点：RocketMQ 容器 `brokerIP1=127.0.0.1` + 固定端口映射（9876/10911/10909），若 CI runner 端口被占用会失败，需改为随机端口方案 |
| 1.3 | CI 绿后人工 Review 合并 | 分支保护策略 | 重点关注 `V9__task_framework.sql` 索引改名（idx_handover_checklist→idx_checklist，V9 未发布安全）与 PERO 三文件挂钩改动 |

## 2. 部署环境变量核对

| 变量 | 本地开发 | docker compose / 生产 | 说明 |
|---|---|---|---|
| `DASHSCOPE_API_KEY` | 必填（sk- 开头） | 必填 | 百炼控制台申请，无 key 时聊天接口不可用 |
| `WIKIAGENT_TASK_ENABLED` | `true`（默认） | `true` | 任务框架总开关 |
| `TASK_MQ` | `local`（默认，零外部依赖） | `rocketmq` | compose 内已置 rocketmq |
| `ROCKETMQ_NAME_SERVER` | `127.0.0.1:9876` | `rmqnamesrv:9876` | TASK_MQ=rocketmq 时必填 |
| `WIKIAGENT_MEMORY_HANDOVER_ADAPTER` | `mysql`（默认） | `mysql` | 回退 file 仅应急用 |
| `WIKIAGENT_MEMORY_FILE_MIGRATION_ENABLED` | `false`（默认） | 一次性置 `true`（见 §4） | 历史 todo.json 迁移开关 |
| `MYSQL_URL/USER/PASSWORD`、`REDIS_HOST/PORT/PASSWORD`、`MILVUS_HOST/PORT` | 按需 | 必填 | 生产切真实中间件；`JPA_DDL` 生产必须 `validate` |

## 3. docker compose 冒烟验证（需本机/服务器有 Docker）

1. `docker compose up -d` 起全量中间件（MySQL 8 / Redis 7 / RocketMQ 5.3.1 / Milvus 2.5+）
2. 验证 RocketMQ 链路：提交 INGEST 任务 → `GET /api/tasks/{taskId}` 观察到 COMPLETED；`GET /api/tasks/{taskId}/stream` SSE 有进度事件
3. 验证降级行为（对照部署指南「降级行为表」）：
   - 停 Redis → 控制指令仍生效（DB 权威复核），进度流静默
   - 停 RocketMQ → `TASK_MQ` 不切也可运行（outbox 补偿 `DispatchCompensationJob` 每 5s 扫描重投）
   - 停 MySQL → 任务框架 Bean 装配失败、应用拒绝启动（**预期行为**，防静默丢任务）
4. 低配机器注意：rmqbroker 未限 JVM 堆，必要时在 compose 加 `JAVA_OPT_EXT` 内存上限

## 4. 历史交接清单一次性迁移（仅存在旧 todo.json 数据时）

1. 部署时置 `WIKIAGENT_MEMORY_FILE_MIGRATION_ENABLED=true` 启动一次
2. 日志确认迁移完成（损坏文件会告警跳过，不阻断启动）
3. 抽查：`SELECT * FROM handover_checklist/handover_node/handover_abandoned_path/handover_data_ref` 行数与旧 `./data/handover/**/todo.json` 对得上
4. **迁完后务必改回 `false`** 并重启（幂等策略为「已存在整文件跳过」，重复开启无副作用但避免每次启动扫描）

## 5. 生产数据库事项

- Flyway 启动时自动执行 V1..V9；生产账号需有 DDL 权限（建 8 张任务/交接表）
- 若此前在测试库跑过改名前的 V9（含 `idx_handover_checklist`），需手工 `DROP INDEX idx_handover_checklist ON handover_node; CREATE INDEX idx_checklist ON handover_node(checklist_id);` 并更新 `flyway_schema_history` 校验和（`validate-on-migrate=false` 时仅索引不一致不阻断，可择机处理）

## 6. 已知设计偏差（记录在案，无需操作，评审知悉）

- **DISPATCH 状态有迁移定义无使用点**：PENDING + enqueue_at 已等价表达，滞留由 DispatchCompensationJob 覆盖；保留状态是为规格 §1.2 迁移表完整
- **RocketMQ 降级为配置式**（`TASK_MQ=local`）而非运行期自动切换；运行期 MQ 故障由 outbox 补偿兜底
- **HEARTBEAT 事件类型保留在枚举**：历史事件行兼容；审计修复后心跳不再落 task_event（防表膨胀），续约只刷 lease_expire_at/heartbeat_at
- **launchAndBridge 订阅窗口**：订阅发生于任务提交之后，理论竞态由 COMPLETED 回放路径兜底（实测量级不可达）
- **测试库隔离**：集成测试共用 H2 持久文件库 `./data/h2/kb`，属后续工程化议题

## 7. 审计修复摘要（本次「检查→优化→检查」10 轮产出）

| 编号 | 问题 | 修复 |
|---|---|---|
| P0（严重） | DB `lease_expire_at` 只在抢租约时写一次，心跳从不续期 → 健康长任务 >30s 被恢复扫描误回收、脑裂重复执行 | 新增 `renewLease`（owner+RUNNING 条件续期）；心跳改走 renewLease；步骤边界 owner 校验抛 `LeaseLostSignalException` 安静让位 |
| P1 | 并发同 bizKey 提交撞 `uk_biz_key` → 500 | submit 捕获 DataIntegrityViolation 回查返回既有任务；SUBMIT 事件移到 save 成功后落库 |
| P2 | HEARTBEAT 每 10s 落 task_event 表膨胀 | 心跳不再 append 事件（枚举保留兼容历史行）；死代码 `updateHeartbeat` 已删 |
| P3 | 任务级 `deadlineSec`（规格 §3.3）未实现 | 步骤边界 `isDeadlineExceeded` 检查，超时按 TIMEOUT 走重试/上限 FAILED |
| P4 | Python 残留（extract_field_index.py） | 删脚本、删 yml 配置、FileHandoverRepository.refreshFieldIndex 空转、端口 javadoc 更正 |
| P5 | handover_node 索引名与规格 §2.5 不符 | `idx_handover_checklist` → `idx_checklist` |
| P8 | waitHuman 重跑同一接管点重复建单撞 `uk_task_step_kind` | save 前查同 (taskId, stepNo, kind) 未决单复用 |
| R6 断裂 | PERO 写侧（MemoryHandover 内存单例）与读侧（HandoverRepository.load）断裂，MySQL 四表运行期无人写入 | MemoryHandover 线程安全化 + 写穿透委托 HandoverRepository（init/completeNode/abandonPath 同步落库，失败仅告警不阻断） |

新增测试：`renewLeaseOnlyOwnerAndRunning`、`concurrentSameBizKeyFallsBackToExisting`、`deadlineExceededRetriesThenFails`、`humanRequiredIsIdempotentAcrossReruns`、`leaseLostAtStepBoundaryYieldsSilently`。
