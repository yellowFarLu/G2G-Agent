# 第三方依赖降级矩阵（子项目 I · AC-I4）

统一观测入口：`CircuitBreakerRegistry`（application/observability/circuit）聚合五类组件状态，
Prometheus 指标 `wikiagent_circuit_state{component,state}`（当前态标签 =1），
CLOSED→OPEN 跳变在注册表记录 `lastOpenedAt` 并打 WARN 日志。
限流/背压拒绝统一返回 HTTP 429 + `Retry-After`，并写 `audit_log`（event_type=`RATE_LIMITED`）。

| 组件 | 故障现象 | 检测方式 | 降级行为 | 用户可见影响 | 恢复方式 | 相关配置 / 类 |
|---|---|---|---|---|---|---|
| **milvus**（向量/BM25 检索） | hybridSearch 连接拒绝/超时（约 10s） | 检索调用抛异常即置 `milvusDisabledUntil=now+60s`，冷却期不再尝试；观测经反射只读该字段 | 本地中文 bigram + LIKE 关键词检索（kb_child_chunk），内存按命中词数打分取 subTopk；rerank/父块组装链路不变 | 仍有检索结果与引用，但相关性弱于向量混合检索（字面重合优先），日志 WARN | 60s 冷却后自动半开试探 Milvus；成功即恢复向量检索 | `RetrievalService`（service/retrieve，FALLBACK_COOLDOWN_MS=60s）；`MilvusCircuitStateSupplier`（反射只读，业务类未开放 getter，失败回退 CLOSED） |
| **parse / rerank**（OCR/ASR/LAYOUT/TABLE 文档 AI） | 供应商超时/网络错误/5xx | 每能力独立熔断：连续失败达阈值（默认 3）→ OPEN 60s；非重试错误（401/参数）不累计 | 熔断打开时该能力立即短路（不再调用供应商），流水线跳过对应 AI 能力；rerank 不可用或抛错时检索结果保持原序，不阻断 | 解析文档缺少对应结构化产物（如无 OCR 文本/表格结构），但管道不报错；检索不重排（顺序=召回序） | OPEN 60s 后半开试探一次，成功关闭、失败重新计时 | `wikiagent.parse.circuit-failure-threshold`、`wikiagent.parse.circuit-open-sec`、`max-attempts`、`retry-backoff-base-ms`；`ProviderExecutor`、`ProviderCircuitBreaker`；`ParseCircuitStateSupplier` |
| **llm**（聊天/意图模型多 provider） | API Key 缺失、provider 调用连续失败 | 每 provider 独立 `LlmCircuitBreaker`（默认连续失败阈值打开）；降级链按候选顺序尝试 | 失败/被熔断 provider 跳过并转下一个，结果携带 fallbackFrom；全部不可用时链尾 NoOp 空响应兜底（degraded=true，model=none），链路不抛异常；每次尝试落 model_call_log | 对话不返回 5xx；无可用模型时为空回答/兜底话术（上层可据此返回降级提示），响应变慢（重试+退避） | 熔断到期后半开试探；成功 provider 立即服务，recordSuccess 关闭熔断 | `ChatModelProviderChain`、`LlmCircuitBreaker`；`LlmChainCircuitStateSupplier`（反射只读 candidates/breaker，业务类未开放 getter，失败回退 CLOSED）；打点 `wikiagent.model.calls/tokens/cost` |
| **redis**（租约/控制标志/进度/stream 协调 + 分布式限流预留） | Redis 未部署/不可达 | `wikiagent.redis.enabled=false`（开发默认）即视为降级态 OPEN | 协调层运行在 JVM 单机实现（DB 行字段租约/内存控制标志）；限流为单机内存令牌桶（容量 60/min/用户+租户，线性补充），不依赖 Redis | 单实例功能完整；多实例水平扩展时租约与限流不跨进程（多实例限流需在网关层或后续提供 Redis Lua 令牌桶） | 配置 `wikiagent.redis.enabled=true` 并连通 Redis 后回到 CLOSED（运行时瞬时断线探测待协调组件暴露健康位后补充） | `wikiagent.redis.enabled`；`RedisDegradedStateSupplier`；`RateLimitFilter`、`SimpleTokenBucket`（infrastructure/observability/ratelimit） |
| **rocketmq**（任务异步投递/调度） | NameServer/Producer 不可用或未部署 | `wikiagent.task.mq=local`（开发默认）→ OPEN；rocketmq 模式按 RocketMqHealthIndicator（UP/DOWN）判定 | 任务走 JVM 本地调度器（LocalTaskDispatcher + TaskWorker 轮询/心跳），状态机、租约、重试/补偿任务全部在 DB 上运转 | 单实例任务可正常提交与完成（秒级）；无跨进程消息堆积与消费组能力，多实例下的调度分工受限 | 部署 RocketMQ 并置 `wikiagent.task.mq=rocketmq`，健康检查 UP 后 CLOSED | `wikiagent.task.mq`；`LocalTaskDispatcher`、`RocketMqHealthIndicator`；`RocketMqLocalStateSupplier` |

## 过载保护（队列背压）

- 入口令牌桶：`POST/GET /api/**` 按 `X-Tenant-Id`（缺省 default）+ `X-User-Id`（缺省 anonymous）维度限流，
  默认 60 请求/分钟，超限 429 + `Retry-After`，审计 event_type=`RATE_LIMITED`（含 tenantId/path）。
- 租户并发背压：`wikiagent.task.tenant-max-concurrent>0` 时，`POST /api/tasks` 前查该租户 RUNNING 数，
  达上限直接 429（Retry-After 默认 5s，审计 detail reason=`TASK_BACKPRESSURE`）。
- 观测/背压组件全部 fail-open：查询或审计写入失败只打 WARN，绝不阻断业务提交。

## 统一恢复策略

1. **自动半开试探**：milvus/parse/llm 熔断均为冷却到期后放行一次试探请求，成功即 CLOSED、失败重新计时。
2. **配置切换恢复**：redis/rocketmq 通过 enabled/mq 配置切换回分布式实现，状态随下次扫描（默认 15s）反映到指标。
3. **观测不干扰业务**：所有 supplier 读取异常一律安全回退 CLOSED 并 WARN；指标/审计失败不影响主链路。

## 已知边界（诚实声明）

- LLM 链与 Milvus 状态当前以反射只读获取（AC-I 约束不允许修改业务类）；字段结构变化时回退 CLOSED，
  待两类业务组件开放公开访问器后改为直读。
- Redis 启用态下的运行时瞬时断线探测、RocketMQ 消息发送失败的生产端熔断，尚未接入注册表。
- 令牌桶为单机实现，多实例部署需网关全局限流或后续 Redis 分布式令牌桶。
