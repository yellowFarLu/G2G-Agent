# 🧭 G2G Agent

**不止是知识库检索 —— 一个具备记忆、规划、治理与自省能力的企业级知识智能体**

*Java 21 · Spring Boot 3 · Spring AI · Milvus 混合检索 · DDD 架构*

---

## 💡 为什么是 G2G Agent？

大多数"企业知识库问答"只是 RAG 三件套：向量化、召回、拼 Prompt。**G2G Agent 把它做成了一条完整的智能体工程闭环**：

> 🔍 **答不上来时，它会联网搜索、或用模型自身知识兜底，并诚实标注答案来源**
> 🧠 **它记得你说过的每句话（短期）、你是谁（长期）、以及历史发生的每个事件（事件库）**
> 🗺️ **遇到复杂任务，它会先规划、再逐步执行、自我反思、持续优化（PERO 架构）**
> 📊 **它知道自己答得好不好 —— 反馈、指标、链路追踪、知识过期治理，一应俱全**
> 🛡️ **提示词注入、PII 泄露、中间件宕机 —— 全都有护栏与降级预案**

---

## 🏗️ 一图看懂全链路

```text
👤 用户（Web UI）
 │
 ▼
🛡️ 输入安全网关 ── 防注入 · 防攻击 · PII 脱敏（全链路第一站）
 │
 ▼
🚦 LLM 路由层 ── qwen-flash 意图识别，成本最优
 ├─ 简单任务 ──────────────► qwen-plus
 ├─ 复杂任务 ──────────────► qwen-max
 └─ 企业知识内容（人名/制度/流程）──► 强制 mode=search 走知识库
 │
 ▼
🤖 Agent 执行层 · PERO 主循环
 │   📋 Plan 任务规划，生成任务清单
 │   ►  ⚡ Execute —— ReAct 逐节点执行
 │   │     ├─ DomainSupervisor 多 Agent 领域路由
 │   │     └─ 🧰 Spring AI @Tool 工具调用
 │   ◄── 🔍 Reflect 反思（未达标则回到 Execute）
 │   ►  📈 Optimize 持续优化
 │
 ├──► 🔎 混合检索 · Milvus
 │        Dense 向量 + BM25 稀疏双路召回
 │        ►  RRF 融合重排 → Top-K → 父子索引上下文
 │
 ├──► 🧠 分层记忆
 │        ├─ 短期记忆 Redis（≤ 20 轮 / TTL 24h）
 │        ├─ 用户档案 MySQL
 │        ├─ 历史事件库 Milvus（父子索引）
 │        └─ 交接清单 todo.json（任务节点实时交接）
 │
 ▼
🛡️ 输出网关 ── 全量校验通过后才允许发送
 │
 ▼
👤 用户（SSE 流式输出，答案附带来源标注）
```

---

## ✨ 核心特性

### 🎯 智能问答
| 能力 | 说明 |
|---|---|
| **混合检索 RAG** | Dense 向量 + BM25 稀疏双路召回，RRF 融合重排，父子索引控制上下文预算 |
| **诚实兜底** | 知识库未命中时：联网搜索（DashScope `enable_search`）→ 模型自身知识，答案强制声明来源属性，绝不伪装成知识库内容 |
| **四层路由** | 多 Agent → v1-v2 Orchestrator → AgentRag → Legacy，`ObjectProvider` 优雅解决 Bean 互斥 |
| **SSE 流式** | 打字机式输出，答案气泡附带会话标签与「查链路」直达入口 |

### 🧠 分层记忆（横向扩展友好）
| 层级 | 存储 | 说明 |
|---|---|---|
| 短期记忆 | **Redis** | 会话级，最多 20 轮，`sessionId + userId` 前缀隔离，TTL 24h |
| 用户画像 | **MySQL** | 以 `userId` 为主键，身份/权限/偏好 |
| 历史事件 | **Milvus** | 父子索引事件库，chunk 级软删除（`is_active`） |
| 交接清单 | **todo.json** | 每个任务节点实时生成，含原始请求、已执行节点、放弃路径、数据索引 |

### ⚙️ 可靠任务框架
| 能力 | 说明 |
|---|---|
| **任务框架** | MySQL 状态机（权威真相源）+ RocketMQ 投递（本地 `TASK_MQ=local` 零中间件降级）+ Redis 协调：入库/Agent 任务 bizKey 幂等提交、步骤级 checkpoint 断点续跑、暂停/取消/人工接管全留痕、Worker 崩溃后 60s 内自动回收续跑，交接清单 MySQL 化不再产生 todo.json |

### 🚦 LLM 成本路由
```
用户提问 ──► qwen-flash（意图识别，最便宜）
                ├─► 简单任务 ──► qwen-plus
                ├─► 复杂任务 ──► qwen-max
                └─► 企业知识内容（人名/制度/流程）──► 强制 mode=search 走知识库
```

### 📊 知识治理闭环
- **冲突检测与解决**：6 种冲突类型 + 误报忽略，双栏对照式合并评审，`is_active` 软删除
- **指标看板**：每日 02:00 定时聚合，`staleScore` = 时间衰减（τ=180d）+ 30 天频次，知识"过期嫌疑"一目了然
- **反馈飞轮**：有用/无用按钮 → `kb_feedback` 落库 → 应用率（Utility Rate）实时统计

### 🔭 全链路可观测
- 自研最小链路追踪：每个会话可查完整执行 Span（路由 → 检索 → 生成）
- 检索指标以 **RETRIEVED / CITED** 粒度在最终源头埋点（拒绝虚报）
- Prometheus `/actuator/prometheus` 原生暴露，4-Tab 可观测大屏

### 🛡️ 安全与韧性
| 场景 | 方案 |
|---|---|
| 提示词注入 / 恶意输入 | 输入安全网关，置于全链路最前端 |
| 回答合规 / 数据脱敏 | 输出网关全量校验（非流式路径）+ Presidio PII sidecar |
| Milvus 宕机 | 60 秒熔断 + 中文 bigram 关键词本地降级，**检索不中断** |
| LLM 调用抖动 | Resilience4j 限流 / 重试 / 降级 |
| 中间件全部缺失 | 零依赖降级启动：H2 文件库 + NoOpChatModel，**依旧健康 UP** |

---

## 🚀 快速开始

### 方式一：30 秒零依赖体验（推荐先跑这个）

无需 Redis、无需 Milvus、无需 API Key —— 一切自动降级，全流程可观测：

```bash
git clone <your-repo-url> && cd wiki-Agent
mvn spring-boot:run
# ✅ 约 17 秒启动完成，打开 http://localhost:8080
```

> 💡 配置 `DASHSCOPE_API_KEY` 后即可解锁真实大模型回答；配置 `MILVUS_HOST` 后解锁向量检索。

### 方式二：Docker 全家桶（生产形态）

一条命令拉起 MySQL + Redis + Milvus（etcd/minio）+ Presidio + Nginx + **双应用副本**：

```bash
export DASHSCOPE_API_KEY=sk-xxx        # 阿里云 DashScope
docker-compose up -d
# Nginx sticky-session 入口:  http://localhost
# 应用副本: http://localhost:8081 / 8082
```

开箱即验证横向扩展：Nginx 按 `X-User-Id` 粘性会话，Redis 集中 Session 兜底，任意副本均可服务。

### 常用环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `DASHSCOPE_API_KEY` | — | 阿里云百炼 API Key（模型能力开关） |
| `MILVUS_HOST` / `MILVUS_URI` | `localhost:19530` | Milvus 向量库地址 |
| `MYSQL_URL` | H2 文件库（MODE=MySQL） | 生产切 MySQL，Flyway 自动迁移 |
| `REDIS_HOST` | `localhost` | 短期记忆 / 分布式锁 |
| `SESSION_STORE` | `none` | 设为 `redis` 启用集中式 Session |
| `WIKIAGENT_FALLBACK_WEB_SEARCH_ENABLED` | `true` | 知识库未命中联网搜索兜底 |
| `WIKIAGENT_FALLBACK_OWN_KNOWLEDGE_ENABLED` | `true` | 知识库未命中模型自身知识兜底 |
| `WIKIAGENT_CHAT_MODEL` | `qwen-plus` | 主对话模型（`text-embedding-v4` 向量） |
| `TASK_MQ` | `local` | 任务投递通道：`local`（JVM 本地调度）/ `rocketmq` |
| `ROCKETMQ_NAME_SERVER` | `127.0.0.1:9876` | RocketMQ NameServer 地址（`TASK_MQ=rocketmq` 时生效） |

---

## 🖥️ 四大页面一览

| 页面 | 看点 |
|---|---|
| 💬 **对话页** | 流式问答 · 会话 ID 可见可复制 · 反馈按钮 · 来源标注 · 路由可视化 |
| 📚 **知识库管理** | 文档上传 + 领域标签 · 8 大业务域垂直隔离 · 身份权限控制检索范围 |
| 🧪 **知识治理** | KPI 看板 + 过期嫌疑表 · 冲突双栏对照合并评审 · 一键忽略误报 |
| 📈 **可观测** | 4-Tab 大屏：指标 / 链路追踪 / 网关安全 / 知识明细，Prometheus 原生对接 |

---

## 🔌 API 速览

```
POST /api/chat                          # SSE 流式问答（主入口）
GET  /api/chat/sessions                 # 会话列表 / 历史
GET  /api/documents                     # 文档管理（上传/检索/删除）
POST /api/conflicts/scan                # 知识冲突扫描
GET  /api/conflicts/{id}/diff           # 冲突双栏对照
POST /api/feedback                      # 有用 / 无用反馈
GET  /api/metrics/dashboard             # 指标看板
GET  /api/metrics/aggregation           # 每日 02:00 聚合结果
GET  /api/trace/session/{sessionId}     # 全链路执行 Span
GET  /api/observability/*               # 4-Tab 可观测数据源
GET  /actuator/prometheus               # Prometheus 指标
GET  /api/health                        # 健康检查（含各中间件状态）
```

---

## 📁 工程结构（DDD 四层）

```
src/main/java/com/wikiagent/
├── domain/                  # 领域层：核心模型与规则
├── application/             # 应用层
│   ├── agent/pero/          #   Plan-Execute-Reflect-Optimize 主循环
│   ├── multiagent/          #   DomainSupervisor 多 Agent 领域路由
│   ├── gateway/             #   输入/输出安全网关
│   ├── knowledge/           #   知识治理（冲突解决）
│   └── identity/            #   用户身份与权限
├── infrastructure/          # 基础设施层（全部可插拔降级）
│   ├── memory/              #   redis / mysql / milvus / inmemory / file 五实现
│   ├── llm/                 #   DashScope 多模型工厂 + 路由器
│   ├── security/            #   注入检测 / PII 脱敏
│   ├── tool/                #   ToolRegistry / ToolContextFactory / NodeExecutor
│   └── trace/               #   自研链路追踪
└── interfaces/              # 接口层：REST 控制器（chat / metrics / trace / ...）
```

> 设计原则：**每个中间件都有本地降级实现**。开发零依赖、生产可替换，同一套业务代码两条路都能跑。

---

## ✅ 测试与验证

```bash
mvn test        # 30/30 全部通过
```

已实测验证的完整链路：

> 上传文档 → 降级检索命中 → 反馈上报 200 → 看板指标更新 → 链路可回查

---

## 📚 深入阅读

| 文档 | 内容 |
|---|---|
| [PRODUCTION_AGENT_PLAN.md](docs/PRODUCTION_AGENT_PLAN.md) | 完整技术方案（v3，含 23 章实施校正记录） |
| [技术方案.md](docs/技术方案.md) | 架构设计细节 |
| [部署指南.md](docs/部署指南.md) | 生产部署手册 |

---

**G2G Agent** — 让企业知识真正"活"起来的智能体工程实践 🚀
