# 🧭 Wiki Agent

**不止是知识库检索 —— 一个具备记忆、规划、治理与自省能力的企业级知识智能体**

*Java 21 · Spring Boot 3.5 · Spring AI 1.1.2 · Milvus 混合检索 · RocketMQ 任务框架 · Next.js 管理台 · DDD 架构*

---

## 💡 为什么是 Wiki Agent？

大多数"企业知识库问答"只是 RAG 三件套：向量化、召回、拼 Prompt。**Wiki Agent 把它做成了一条完整的智能体工程闭环**：

> 🔍 **答不上来时，它会联网搜索、或用模型自身知识兜底，并诚实标注答案来源**
> 🧠 **它记得你说过的每句话（短期）、你是谁（长期）、以及历史发生的每个事件（事件库）**
> 🗺️ **遇到复杂任务，它会先规划、再逐步执行、自我反思、持续优化（PERO 架构）**
> ⚙️ **重活都进任务框架：排队、幂等、重试、崩溃恢复、人工接管，一个不缺**
> 📊 **它知道自己答得好不好 —— 黄金样本离线评测 7 项指标、反馈飞轮、链路追踪、知识过期治理**
> 🛡️ **提示词注入、PII 泄露、中间件宕机 —— 全都有护栏与降级预案**
> 🎚️ **功能开关 + 灰度发布 + 限流降级，生产运维所需的一张表就能查到**

---

## 🏗️ 一图看懂全链路

```text
👤 用户（Next.js 管理台 / 内置 Web UI）
 │
 ▼
🛡️ 输入安全网关 ── 防注入 · 防攻击 · PII 脱敏 · 信任边界校验（全链路第一站）
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
 │   │     └─ 🧰 Spring AI @Tool 工具调用（PeroToolExecutor 真实分派）
 │   ◄── 🔍 Reflect 反思（未达标则回到 Execute）
 │   ►  📈 Optimize 持续优化
 │
 ├──► 🔎 混合检索 · Milvus
 │        Dense 向量 + BM25 稀疏双路召回
 │        ►  RRF 融合 → DashScope rerank 精排（灰度门控）→ Top-K → 父子索引上下文
 │
 ├──► 🧠 分层记忆
 │        ├─ 短期记忆 Redis（≤ 20 轮 / TTL 24h）
 │        ├─ 用户档案 MySQL（userId 主键）
 │        ├─ 历史事件库 Milvus（父子索引，软删除）
 │        └─ 交接清单 MySQL（原始请求/已执行节点/放弃路径/数据索引）
 │
 ▼
🛡️ 输出网关 ── 全量校验通过后才允许发送（非流式路径）
 │
 ▼
👤 用户（SSE 流式输出，答案附带来源标注）
```

重活（文档入库、Agent 长任务）不走在请求线程里，而是进入任务框架：

```text
提交 ─► MySQL 任务状态机（真相源，bizKey 幂等）
       ─► RocketMQ 投递（Tag 区分 INGEST/AGENT，本地 TASK_MQ=local 零中间件降级）
       ─► Worker 领取（Redis 租约 + 心跳续租）
       ─► 步骤级 Checkpoint 断点续跑 ─► 完成 / 指数退避重试 / 死信
       ─► 暂停 · 恢复 · 取消 · 人工接管（Redisson 锁 + CAS，全程留痕）
       ─► 崩溃任务 60s 内由 RecoveryJob 自动回收续跑
```

---

## ✨ 核心特性

### 🎯 智能问答
| 能力 | 说明 |
|---|---|
| **混合检索 RAG** | Dense 向量 + BM25 稀疏双路召回，RRF 融合，**DashScope rerank 精排**（默认开启，无 Key 自动跳过、失败保持原序），父子索引控制上下文预算 |
| **诚实兜底** | 知识库未命中时：联网搜索（DashScope `enable_search`）→ 模型自身知识，答案强制声明来源属性，绝不伪装成知识库内容 |
| **四层路由** | 多 Agent → v1-v2 Orchestrator → AgentRag → Legacy，`ObjectProvider` 优雅解决 Bean 互斥 |
| **SSE 流式** | 打字机式输出，答案气泡附带会话标签与「查链路」直达入口 |

### 🧠 分层记忆（横向扩展友好）
| 层级 | 存储 | 说明 |
|---|---|---|
| 短期记忆 | **Redis** | 会话级，最多 20 轮，`sessionId + userId` 前缀隔离，TTL 24h |
| 用户画像 | **MySQL** | 以 `userId` 为主键，身份/权限/偏好 |
| 历史事件 | **Milvus** | 父子索引事件库，chunk 级软删除（`is_active`） |
| 交接清单 | **MySQL**（四张表） | 每个任务节点实时生成：原始请求、已执行节点、放弃路径、数据参考索引；已彻底告别本地 todo.json |

### ⚙️ 通用任务框架
| 能力 | 说明 |
|---|---|
| **状态机为真相源** | MySQL `task_instance/task_step/task_event/human_task` 四表；RocketMQ 只投递，Redis 只协调（租约/锁/标志） |
| **幂等提交** | `biz_key` 唯一约束防重复；并发冲突返回已存在任务 |
| **断点续跑** | 步骤级 Checkpoint，崩溃恢复从最后一个未完成步骤继续，已 DONE 步骤不重跑 |
| **退避重试** | `attempt` 控制，指数退避 10s/30s/2min，上限 3 次，耗尽置 FAILED |
| **人工接管** | 两种模式：人工输入后自动恢复 / 人工直接终结；Redisson 锁 + `lock_version` CAS 防双人接管 |
| **崩溃自愈** | 租约过期 60s 内 RecoveryJob 回收；心跳续租带 owner 校验防误杀健康长任务 |
| **本地降级** | RocketMQ 不可用降级 JVM 本地调度；Redis 不可用降级 MySQL 行字段；MySQL 不可用拒绝启动（不静默丢任务） |

### 📄 文档智能解析流水线
- Provider SPI（`wikiagent.parse.provider`）：`none` 纯文本 / `dashscope` 多模态（OCR、语音转写、版面分析、表格提取）
- 统一调用策略：超时 + 2 次指数退避 + 60s 熔断（独立熔断状态机）
- 结构化字段提取：字段级置信度，低置信度自动转入人工复核

### 🧬 数据血缘与版本
- 原文 → 页码 → 模型结果 → 人工修改，全链路可追溯
- 文档多版本管理与双版本 diff；引用可解析到具体版本的具体子块
- API：`/api/documents/{docId}/lineage`、`/versions`、`/versions/{a}/diff/{b}`

### 📏 确定性规则引擎 + 人工复核
- 规则集版本化管理，确定性计算（零 LLM），异常样本自动成案
- 复核留痕：每个 ReviewCase 记录处理人、旧值/新值、结论，全程可审计

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

### 🧪 离线评测体系
- 黄金样本驱动，**7 项确定性指标**（零 LLM 评判）：检索命中率、引用正确率、字段准确率、人工修改率、评判错误率、单轮成本、响应时延
- 四大评测套件：检索 / 解析 / 规则 / 异常，Milvus 与 Embedding 以"立即失败"端口强制走本地降级，**零 Docker 零外部依赖**
- API：`POST /api/eval/run`、`GET /api/eval/report/latest`；基线与口径见 `docs/operations/eval-baseline.md`

### 🔭 全链路可观测
- 自研最小链路追踪：每个会话可查完整执行 Span（路由 → 检索 → 生成）
- 检索指标以 **RETRIEVED / CITED** 粒度在最终源头埋点（拒绝虚报）
- Prometheus `/actuator/prometheus` 原生暴露，可观测大屏 + 灰度决策打点 `wikiagent.gray.decision`
- Resilience4j 限流 / 重试 / 背压（`wikiagent.ratelimit.enabled`）

### 🛡️ 安全与韧性
| 场景 | 方案 |
|---|---|
| 提示词注入 / 恶意输入 | 输入安全网关 + Spotlighting 对抗 + 双锁规则，置于全链路最前端 |
| 信任边界 | `X-User-Id` 共享密钥校验（`WIKIAGENT_INTERNAL_SECRET`，生产必配） |
| 回答合规 / 数据脱敏 | 输出网关全量校验（非流式路径）+ Presidio PII sidecar + PII 最小化外发 |
| Milvus 宕机 | 60 秒熔断 + 中文 bigram 关键词本地降级，**检索不中断** |
| LLM 调用抖动 | Resilience4j 限流 / 重试 / 降级 |
| 中间件全部缺失 | 零依赖降级启动：H2 文件库 + NoOpChatModel，**依旧健康 UP** |

### 🎚️ 灰度发布与功能开关
- `GrayReleaseService`：SHA-256(特性:身份) 稳定分桶（重启/扩缩容不漂移），denylist > allowlist > percent，未配置特性不门控
- 首个决策点：`rerank`；扩量调 percent、回滚调 0 或加 denylist，每次决策 Micrometer 打点
- 全部 `@ConditionalOnProperty` 开关登记表：[docs/operations/feature-toggles.md](docs/operations/feature-toggles.md)

---

## 🚀 快速开始

### 方式一：30 秒零依赖体验（推荐先跑这个）

无需 Redis、无需 Milvus、无需 API Key —— 一切自动降级，全流程可观测：

```bash
git clone https://github.com/yellowFarLu/wiki-Agent.git && cd wiki-Agent
mvn spring-boot:run        # 或 ./start.sh
# ✅ 约 17 秒启动完成，打开 http://localhost:8080
```

> 💡 配置 `DASHSCOPE_API_KEY` 后即可解锁真实大模型回答；配置 `MILVUS_URI` 后解锁向量检索。

### 方式二：Docker 全家桶（生产形态）

一条命令拉起 MySQL + Redis + Milvus（etcd/minio）+ RocketMQ + Presidio + Nginx + **双应用副本**：

```bash
export DASHSCOPE_API_KEY=sk-xxx        # 阿里云 DashScope
docker-compose up -d
# Nginx sticky-session 入口:  http://localhost
# 应用副本: http://localhost:8081 / 8082
```

> 📄 想了解文档如何被解析、切分、向量化写入 Milvus？详见下文「📄 文档解析与入库流水线」。

开箱即验证横向扩展：Nginx 按 `X-User-Id` 粘性会话，Redis 集中 Session 兜底，任意副本均可服务。

### 常用环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `DASHSCOPE_API_KEY` | — | 阿里云百炼 API Key（模型能力开关） |
| `MYSQL_URL` / `MYSQL_USER` / `MYSQL_PASSWORD` | H2 文件库（MODE=MySQL） | 生产切 MySQL，Flyway 自动迁移 |
| `MILVUS_URI` | `localhost:19530` | Milvus 向量库地址 |
| `REDIS_ENABLED` / `REDIS_HOST` | `false` / `localhost` | Redis 总开关；关闭时降级 JVM 本地协调/记忆 |
| `WIKIAGENT_INTERNAL_SECRET` | — | 信任边界共享密钥（生产必配） |
| `WIKIAGENT_TASK_ENABLED` | `true` | 任务框架总开关；false 时任务 API 返回 503 |
| `TASK_MQ` | `local` | 任务投递通道：`local`（JVM 本地调度）/ `rocketmq` |
| `ROCKETMQ_NAME_SERVER` | `127.0.0.1:9876` | RocketMQ NameServer（`TASK_MQ=rocketmq` 时生效） |
| `WIKIAGENT_PERO_ENABLED` | `true` | PERO Agent 子系统开关；false 回退 v1-v2 |
| `WIKIAGENT_PARSE_PROVIDER` | `none` | 文档解析：`none` / `dashscope` 多模态 |
| `WIKIAGENT_RERANK_ENABLED` | `true` | rerank 精排；无 Key 自动跳过、失败保持原序 |
| `WIKIAGENT_CHAT_MODEL` | `qwen-plus` | 主对话模型（`text-embedding-v4` 向量） |
| `WIKIAGENT_FALLBACK_WEB_SEARCH_ENABLED` | `true` | 知识库未命中联网搜索兜底 |
| `WIKIAGENT_FALLBACK_OWN_KNOWLEDGE_ENABLED` | `true` | 知识库未命中模型自身知识兜底 |

> 完整开关登记表（默认值/环境变量/变更风险）：[docs/operations/feature-toggles.md](docs/operations/feature-toggles.md)

---

## 🖥️ 前端

### Next.js 管理台（`frontend/`，生产形态）
| 页面 | 看点 |
|---|---|
| 💬 **对话页** | SSE 流式问答 · 引用角标 + 来源弹层 · 反馈按钮 |
| 📤 **材料上传** | 拖拽/多文件/业务域标签/重复提示 |
| 📋 **任务中心** | 任务列表/详情 · 步骤级 SSE 实时进度 · 事件流 · 暂停/恢复/取消/重试 |
| 🧾 **结构化结果** | 字段表 + 置信度 + 冲突标记 · 来源查看（页码/片段） |
| 🧑‍⚖️ **人工工作台** | 复核/工具审批/解密队列处置 · 历史版本 diff |
| ⚙️ **身份设置** | 用户身份与业务域权限配置 |

```bash
cd frontend && npm install && npm run dev   # 开发代理至 8080
```

### 内置静态 UI（`src/main/resources/static/`，零构建）
💬 对话页 · 📚 知识库管理 · 🧪 知识治理 · 📈 可观测 4-Tab 大屏，随后端启动即用。

### 知识领域与身份
8 大业务域垂直隔离：行业解决方案 / 商家中心 / 服务商 / 干线 / 关务 / 结算 / 首公里 / 轨迹；
5 种业务身份（admin / 业务 / 产品 / 技术 / 测试）由管理员配置，控制知识检索范围。

---

## 🔌 API 速览

```
# 对话与反馈
POST /api/chat                              # SSE 流式问答（主入口）
GET  /api/chat/sessions                     # 会话列表 / 历史
POST /api/feedback                          # 有用 / 无用反馈

# 任务框架
POST /api/tasks                             # 幂等提交（bizKey）
GET  /api/tasks | /api/tasks/{taskId}       # 列表 / 详情（含 steps / events）
POST /api/tasks/{taskId}/suspend|resume|cancel|replay
POST /api/human-tasks/{id}/claim|resolve    # 人工接管 / 处置

# 文档 / 血缘 / 规则
GET  /api/documents                         # 文档管理（上传/检索/删除）
GET  /api/documents/{docId}/lineage         # 血缘溯源（页码级）
GET  /api/documents/{docId}/versions/{a}/diff/{b}
GET  /api/review-cases                      # 复核案件队列 / 处置

# 治理与观测
POST /api/conflicts/scan                    # 知识冲突扫描
GET  /api/metrics/dashboard                 # 指标看板（每日 02:00 聚合）
GET  /api/trace/session/{sessionId}         # 全链路执行 Span
GET  /api/observability/*                   # 可观测大屏数据源
POST /api/eval/run                          # 离线评测（7 指标）

# 管理与运维
GET  /api/admin/identity/{userId}           # 身份与域权限管理
GET  /actuator/prometheus                   # Prometheus 指标
GET  /api/health                            # 健康检查（含各中间件状态）
```

---

## 📁 工程结构（DDD 四层）

```
src/main/java/com/wikiagent/
├── domain/                    # 领域层：核心模型与规则（纯逻辑，无框架依赖）
│   ├── task/                  #   任务状态机 / 步骤 / 事件 / 人工任务
│   ├── eval/                  #   评测模型 + 7 个指标计算器
│   ├── identity/              #   业务身份 / 8 大领域 / 子域
│   └── ...
├── application/               # 应用层
│   ├── agent/pero/            #   Plan-Execute-Reflect-Optimize 主循环 + PeroToolExecutor
│   ├── task/                  #   提交/派发/Worker/控制/恢复/看门狗 + handler(INGEST/AGENT)
│   ├── eval/                  #   EvalRunner + 四大评测套件
│   ├── gray/                  #   GrayReleaseService 灰度决策
│   ├── multiagent/            #   DomainSupervisor 多 Agent 领域路由
│   ├── gateway/               #   输入/输出安全网关
│   ├── knowledge/             #   知识治理（冲突解决）
│   └── identity/              #   用户身份与权限
├── infrastructure/            # 基础设施层（全部可插拔降级）
│   ├── memory/                #   redis / mysql / milvus / inmemory / file 五实现
│   ├── task/                  #   jpa(5+4 表) / mq(RocketMQ+本地降级) / redis(租约锁标志)
│   ├── llm/                   #   DashScope 多模型工厂 + 路由器 + rerank
│   ├── parse/                 #   文档解析 Provider SPI
│   ├── security/              #   注入检测 / PII 脱敏 / 信任边界
│   ├── tool/                  #   ToolRegistry / ToolContextFactory / 5 个真实工具
│   └── trace/                 #   自研链路追踪
└── interfaces/                # 接口层：REST 控制器（chat / task / eval / review / lineage / admin / ...）

frontend/                      # Next.js 14 管理台（App Router + TS + Ant Design + SSE）
```

> 设计原则：**每个中间件都有本地降级实现**。开发零依赖、生产可替换，同一套业务代码两条路都能跑。

---

## ✅ 测试与评测

```bash
mvn verify -Dtest.profile=light    # 本地快速回归：704 测试全绿（不依赖 Docker）
mvn verify                         # 完整验收：单测 + Testcontainers IT（需 Docker，CI 执行）
cd frontend && npm run build && npx playwright test   # 前端 typecheck/lint/build + e2e
```

- **704** 项单测/切片测试 + **43** 项 Testcontainers 集成测试（MySQL/Redis/RocketMQ 真实容器，CI 运行）
- 已实测验证的完整链路：上传文档 → 解析 → 降级检索命中 → 反馈上报 → 看板指标更新 → 链路可回查 → 任务全程留痕
- 离线评测：黄金样本 + 7 项确定性指标，报告落 `eval-reports/`，口径见 [docs/operations/eval-baseline.md](docs/operations/eval-baseline.md)

---

## 🔄 CI / CD

| 工作流 | 触发 | 内容 |
|---|---|---|
| `ci.yml` | 每次 push / PR | 后端 `mvn -B verify`（单测 + Testcontainers IT）+ 前端 typecheck/lint/build + Playwright e2e |
| `eval.yml` | 手动 / 定时 | 离线评测回归，指标基线对比 |
| `release-deploy.yml` | 发布 | 构建镜像 + 部署 + 回滚预案 |

生产手册：[docs/operations/final-manual-checklist.md](docs/operations/final-manual-checklist.md)（环境检查/验收/回滚）· [backup-restore.md](docs/operations/backup-restore.md)（备份恢复）· [degradation-matrix.md](docs/operations/degradation-matrix.md)（降级矩阵）· [trust-boundary.md](docs/operations/trust-boundary.md)（信任边界）

---

## 📚 深入阅读

| 文档 | 内容 |
|---|---|
| [PRODUCTION_AGENT_PLAN.md](docs/PRODUCTION_AGENT_PLAN.md) | 完整技术方案（含实施校正记录） |
| [技术方案.md](docs/技术方案.md) | 架构设计细节 |
| [部署指南.md](docs/部署指南.md) | 生产部署手册 |
| [docs/operations/](docs/operations/) | 运维六篇：开关登记 / 灰度 / 降级矩阵 / 信任边界 / 备份恢复 / 评测基线 |
| [docs/superpowers/](docs/superpowers/) | 任务框架设计规格与 B–J 实施计划（含复查问题登记） |

---

## 📄 文档解析与入库流水线

Wiki Agent 的入库不是"文档 → 向量"一步黑盒，而是 **六步可断点续跑的流水线**，每一步都留痕、可溯源、可重试：

```text
📤 上传 ──► 📖 解析 ──► 🧹 清洗 ──► ✂️ 父子切分 ──► 🔢 向量化 ──► ✅ 索引校验
           (PARSING)  (CLEANING)  (CHUNKING)    (EMBEDDING)  (INDEXING→READY)
```

### 六步详解

| 步骤 | 组件 | 关键逻辑 |
|---|---|---|
| **① 解析** `PARSING` | `DocumentParser` / `RichDocumentParser` | 按扩展名分流：txt/md 直读、pdf 用 PDFBox、docx/xlsx 用 POI、旧版 .doc/.xls 走 Tika 兜底；PDF 逐页提取文本 + OCR 兜底，产出 `ParsedDocument`（含分页/坐标/版面/表格证据） |
| **② 富解析** `PARSING` | `DashScopeDocumentAiProvider` | 可选 Provider SPI：扫描件/图片走 OCR、录音走 ASR、版面分析、跨页表格拼接；加密文档自动转入人工解密 |
| **③ 清洗** `CLEANING` | `TextCleaner` | 去零宽字符、去页码/分隔线、折叠多余空白、去除 ≥3 次重复短行（页眉页脚特征） |
| **④ 父子切分** `CHUNKING` | `ChunkSplitter` | **两级切分**：父块按段落聚合（默认 1500 字符），超长段落按句子边界二次切分（带 200 重叠）；子块在父块内滑窗（默认 500 字符），窗口在句子边界收口，相邻子块带 100 重叠。子块记录 `pageNo` + `versionNo` |
| **⑤ 向量化** `EMBEDDING` | `DashScope EmbeddingModel` | `text-embedding-v4`，批量上限 10 条/次；可选 Embedding 精确缓存（Redis）命中不重复调用 |
| **⑥ 索引写入** `INDEXING` | `MilvusStoreService` | 子块向量写入 Milvus Collection（`kb_child_chunk`），父块纯 MySQL 存储做上下文回溯；`is_active` 软删除支持版本重解析 |

> 关键设计：**幂等切分**（当前版本 active 子块已存在则跳过）、**软删除**（重解析旧版本子块 `is_active=false` 保留历史）、**页码粗映射**（子块前 20 字符在页文本中定位来源页）。

### 数据血缘（Provenance）

每个产物（Artifact）都有血缘链：

```text
RAW_FILE ──► PARSED_TEXT ──► CLEANED_TEXT ──► PARENT_CHUNK ──► CHILD_CHUNK ──► FIELD（结构化字段）
                │                │
                ▼                ▼
            OCR_PAGE         STITCHED_TABLE
```

- 原文 → 页码 → 模型结果 → 人工修改，全链路可追溯
- API：`GET /api/documents/{docId}/lineage`、`/versions`、`/versions/{a}/diff/{b}`

### 两种驱动方式

| 方式 | 触发条件 | 特点 |
|---|---|---|
| **任务框架**（推荐） | `wikiagent.task.enabled=true` | `IngestTaskHandler` 按步骤调用，断点续跑，崩溃恢复，人工接管 |
| **旧异步入口** | `enabled=false` 或兼容路径 | `@Async ingest(...)` 顺序执行，异常兜底置 FAILED |

---

## 🕸️ GraphRAG 知识图谱

Wiki Agent 在向量检索之上叠加 **LightRAG 风格的实体关系图谱**，捕捉文档中的人物/组织/产品/概念及其关系，让"与某实体相关的所有知识"可以被图结构一次召回。

### 方案选型

| 方案 | 选用 | 原因 |
|---|---|---|
| **微软 GraphRAG** | ❌ | 社区检测 + 全局摘要成本高（全量重跑），不适合增量入库 |
| **HippoRAG** | ❌ | 神经关联记忆创新性强，但工程落地复杂度高 |
| **LightRAG**（选用） | ✅ | 双层检索（实体级 + 主题级），增量友好，图存 MySQL 零额外依赖 |

### 架构

```text
入库时（每 chunk）                    检索时
┌─────────────┐                 ┌──────────────┐
│ chunk 文本   │                 │ 用户查询      │
└──────┬──────┘                 └──────┬───────┘
       ▼                               ▼
┌─────────────────┐            ┌────────────────┐
│ LLM 实体/关系抽取│            │ 实体名称匹配    │
│ (qwen-plus)     │            │ (graph_entity) │
└──────┬──────────┘            └───────┬────────┘
       ▼                               ▼
┌─────────────────┐            ┌────────────────┐
│ MySQL 图存储     │            │ 1-hop 邻居扩展  │
│ graph_entity    │            │ (graph_relation)│
│ graph_relation  │            └───────┬────────┘
└─────────────────┘                    ▼
                               ┌────────────────┐
                               │ 关联 chunk → 父块│
                               │ 累积进检索结果  │
                               └────────────────┘
```

### 数据模型（V17 迁移）

- **`graph_entity`**：`name + type` 唯一约束，冲突时 upsert 合并 description；`is_active` 软删除
- **`graph_relation`**：`source + target + type` 唯一约束，冲突时累加 weight 合并 description；外键关联实体

### 核心特性

- **特性开关**：`wikiagent.graph.enabled=true`（默认 false），开启后入库流水线自动抽取实体关系
- **实体消歧**：同名同类型实体自动合并，跨文档实体共享图谱
- **图增强检索**：实体匹配 + 邻居扩展补充向量检索未覆盖的关联上下文（`RetrievalService.graphExpand`）
- **前端可视化**：`/graph` 页面 Canvas 力导向图，支持搜索、实体类型着色、点击跳转文档
- **API**：`GET /api/graph/stats`、`/search?q=`、`/entity/{id}`、`/entity/{id}/neighbors`、`/doc/{docId}`

### 诚实边界

- 实体/关系由 LLM 从 chunk 文本抽取，**仅抽取文本中明确提及的内容**，不编造
- 抽取失败不阻断入库主流程（catch + log warn）
- 图谱质量依赖 LLM 抽取能力，错误实体可通过删除文档软下线

---

## 🧩 模块逻辑细节

### PERO 主循环（Plan-Execute-Reflect-Optimize）

```text
用户输入 ──► 📋 Plan（qwen-max 多节点规划，生成 task list）
                │
                ▼
        ⚡ Execute（ReAct 逐节点执行）
                ├─ DomainSupervisor 意图路由（8 大业务域）
                └─ 🧰 ToolRegistry（5 个真实工具：检索/计算/HTTP/数据库/代码执行）
                │
                ▼
        🔍 Reflect（qwen-flash 校验结果是否达标）
                ├─ 达标 ──► 📈 Optimize（经验沉淀到事件库）
                └─ 未达标 ──► 回到 Execute 重试（最多 3 次）
```

### 四层路由降级链

```text
用户请求
    │
    ▼
┌─────────────┐
│  多 Agent    │  ← DomainSupervisor 按意图分发到领域 Agent
│  (PERO v6)  │
└──────┬──────┘
       │ Bean 互斥时 ObjectProvider 优雅降级
       ▼
┌─────────────┐
│  v1-v2      │  ← Orchestrator 简单任务编排
│ Orchestrator│
└──────┬──────┘
       ▼
┌─────────────┐
│  AgentRag   │  ← 纯 RAG 检索 + 生成
└──────┬──────┘
       ▼
┌─────────────┐
│  Legacy     │  ← 旧版单轮问答（兜底）
└─────────────┘
```

### 任务框架状态机

```text
PENDING ──► RUNNING ──► COMPLETED ✅
              │
              ├──► FAILED（可重试，attempt ≤ 3）
              │
              ├──► SUSPENDED（人工暂停）
              │       │
              │       └──► RESUME ──► RUNNING
              │
              └──► CANCELLED（人工取消）
```

- **租约机制**：Worker 领取任务后 `lease_expire_at` 心跳续租，60 秒未续租由 RecoveryJob 回收
- **幂等提交**：`biz_key` 唯一约束，重复提交返回已存在任务
- **Checkpoint**：步骤级持久化，崩溃后从最后一个未完成步骤继续

---

**Wiki Agent** — 让企业知识真正"活"起来的智能体工程实践 🚀
