# 功能开关登记表与生产必需变量

> 对应波3 J2 AC：所有 `@ConditionalOnProperty` 开关在此登记，
> 含默认值、安全含义、环境变量名与变更风险等级。

## 一、业务功能开关（按子系统分组）

| 开关键 | 默认值 | 环境变量 | 安全含义 | 变更风险 |
|---|---|---|---|---|
| `wikiagent.task.enabled` | `true` | `WIKIAGENT_TASK_ENABLED` | 任务框架总开关；false 时全部任务 Bean 不装配，API 返回 503 | 高：业务停摆 |
| `wikiagent.eval.enabled` | `true` | `WIKIAGENT_EVAL_ENABLED` | 离线评测框架；false 时 EvalRunner/EvalController 不装配 | 低：仅影响评测 |
| `wikiagent.pero.enabled` | `true` | `WIKIAGENT_PERO_ENABLED` | PERO Agent 子系统；false 时回退 v1-v2 总开关行为，ReAct 工具链不装配 | 高：对话能力降级 |
| `wikiagent.pero.optimize.enabled` | `false` | `WIKIAGENT_PERO_OPTIMIZE_ENABLED` | PERO 优化器（Reflect-Optimize）；false 时仅 Plan-Execute | 中：无自动优化 |

## 二、基础设施开关

| 开关键 | 默认值 | 环境变量 | 安全含义 | 变更风险 |
|---|---|---|---|---|
| `wikiagent.redis.enabled` | `false` | `REDIS_ENABLED` | Redis 总开关；false 时自动排除 Redisson 自动装配，降级为 JVM 本地协调/限流/记忆 | 中：单机可用，失去分布式能力 |
| `wikiagent.task.mq` | `local` | `TASK_MQ` | 任务消息队列：`local`=JVM 线程池调度；`rocketmq`=RocketMQ | 中：local 仅单机可用 |
| `wikiagent.parse.provider` | `none` | `WIKIAGENT_PARSE_PROVIDER` | 文档解析提供者：`none`=禁用；`dashscope`=DashScope 多模态 | 中：none 时上传后仅纯文本 |
| `wikiagent.rerank.enabled` | `false` | `WIKIAGENT_RERANK_ENABLED` | 重排序开关 | 低 |
| `wikiagent.cache.answer.enabled` | `false` | `WIKIAGENT_CACHE_ANSWER_ENABLED` | 答案缓存；false 防幻觉（推荐生产默认关） | 低 |

## 三、安全与可观测性开关

| 开关键 | 默认值 | 环境变量 | 安全含义 | 变更风险 |
|---|---|---|---|---|
| `wikiagent.observability.enabled` | `true` | — | 可观测性总开关（trace/指标/熔断）；缺省启用 | 低：关闭失去监控 |
| `wikiagent.ratelimit.enabled` | `true` | `WIKIAGENT_RATELIMIT_ENABLED` | 限流总开关；false 时关闭令牌桶与背压 | 高：失去过载保护 |
| `wikiagent.security.input-guardrail-enabled` | `true` | — | 输入安全网关（注入/越权检测） | 高：关闭后攻击面扩大 |
| `wikiagent.security.output-guardrail-enabled` | `true` | — | 输出安全网关（SSE 完成前校验） | 高：关闭后有害内容可能外发 |
| `wikiagent.security.spotlighting-enabled` | `true` | — | Spotlighting 对抗提示注入 | 高 |
| `wikiagent.security.rule-of-two-enabled` | `true` | — | 双锁规则（敏感操作需二次确认） | 高 |
| `wikiagent.security.trust-boundary.enabled` | `true` | `WIKIAGENT_TRUST_BOUNDARY_ENABLED` | X-User-Id 共享密钥校验；false 时仅检查头存在 | 高：生产必须 true |
| `wikiagent.security.pii-minimization.enabled` | `true` | `WIKIAGENT_PII_MINIMIZATION_ENABLED` | PII 最小化外发 | 高 |

## 四、存储适配开关

| 开关键 | 默认值 | 说明 |
|---|---|---|
| `wikiagent.trace.adapter` | `mysql` | trace 持久化：mysql / file |
| `wikiagent.memory.handover-adapter` | `mysql` | handover 持久化：mysql / file |
| `wikiagent.memory.historical-adapter` | `milvus` | 历史事件向量库：milvus |
| `wikiagent.memory.file-migration.enabled` | `false` | handover 文件迁移（一次性） |
| `wikiagent.concurrency.lock.provider` | `redisson` | 分布式锁：redisson（需 Redis） |

## 五、生产必需环境变量（无默认值，启动前必须配置）

| 变量名 | 用途 | 示例 |
|---|---|---|
| `ROCKETMQ_NAME_SERVER` | RocketMQ nameserver 地址（mq=rocketmq 时） | `127.0.0.1:9876` |
| `MILVUS_URI` | Milvus 服务端点 | `http://milvus:19530` |
| `MILVUS_USERNAME` | Milvus 用户名 | — |
| `MILVUS_PASSWORD` | Milvus 密码 | — |
| `WIKIAGENT_INTERNAL_SECRET` | X-User-Id 共享密钥（trust-boundary 启用时必填） | — |
| `DASHSCOPE_API_KEY` | DashScope API Key（真实 LLM 调用时） | — |
| `SPRING_DATASOURCE_URL` | MySQL JDBC URL | `jdbc:mysql://...` |
| `SPRING_DATASOURCE_USERNAME` | 数据库用户名 | — |
| `SPRING_DATASOURCE_PASSWORD` | 数据库密码 | — |

## 六、/actuator/env 可见性

全部开关均通过 Spring Boot `@ConfigurationProperties` 绑定，
`/actuator/env` 在 `management.endpoints.web.exposure.include` 包含 `env` 时可见当前生效值。
生产建议暴露：`health,info,metrics,prometheus`（不含 env，防敏感配置泄露）。
运维需查看开关值时，通过日志启动摘要或本登记文档核对。
