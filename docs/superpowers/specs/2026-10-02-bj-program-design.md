# B–J 子项目群总体设计规格（统一规划 · 分波实施）

日期：2026-10-02 ｜ 基线分支：`feature/task-framework`（子项目 A 已完成，commit 801eaa1，341 测试绿）
需求源：`docs/1001-需求.txt`（16 条；A 已覆盖 1、16，本规格覆盖 2–15）
决策记录：①统一规划+分波实施，一次审批后按波次连续推进，每波独立测试/复查闸门；②前端 React+Next.js；③OCR/ASR/多模态走**可插拔多供应商 SPI**，首批 DashScope；④B–J 继续在 `feature/task-framework` 分支叠加。

---

## 1. 现状基线盘点（已存在资产，B–J 只增不改其契约）

| 领域 | 已有资产 |
|---|---|
| 任务基座(A) | MySQL 状态机+RocketMQ+Redis 协调；INGEST 六步（DOWNLOAD/PARSE/CLEAN/SPLIT/EMBED/VERIFY）与 AGENT 已接入；人工接管 human_task；暂停/取消/重放；SSE 桥 |
| 文档入库 | DocumentController(`POST /api/documents`，file/domain/subDomain，bizKey=`ingest:{sha256}:{owner}`)；DocumentParser（txt/md/pdf/docx/xlsx，PDFBox/POI）；ChunkSplitter 父子切分；派生文件 `_parsed.txt/_cleaned.txt` |
| 知识数据 | V1 kb_document/kb_parent_chunk/kb_child_chunk；V5 knowledge_metadata(chunk_id/doc_id/source_filename/**version**/is_active)；V6 kb_feedback/metric_event/knowledge_metric；V7 冲突决议（软删 is_active） |
| 检索 RAG | RetrievalService 多查询 hybrid（Milvus dense+sparse RRF）+中文 bigram 本地降级+60s 熔断；AgentRagService CRAG；ConflictResolutionService；权限按 domain/subDomain/identity 打标 |
| 模型 | DashScopeMultiModelFactory（qwen-flash 意图→qwen-plus/qwen-max）；PromptComposer；NoOpChatModel 无 key 降级 |
| Agent(PERO) | PeroAgent/ReActExecutor/DomainSupervisor；工具体系 @Tool/ToolCallback/ToolRegistry；AgentTaskHandler（PLAN=1/NODE=100+i/GENERATE=1000） |
| 可观测/安全 | V2 agent_trace（span/token/model_used）；V3 audit_log；GuardrailDetector + Presidio(PII)/AzurePromptShield/LlmJudge/SystemPromptLeak；MetricsAggregationJob 每日 02:00；Actuator+Prometheus；MetricsController |
| 评测雏形 | `src/test/resources/eval/golden.json` + AgentEvalTest + JudgeService（意图/模型/关键词评分） |
| 前端 | 单页 static/index.html + app.js（仅上传/文档列表） |
| 运维 | .github/workflows/ci.yml（JDK21+mvn verify）；docker-compose（MySQL/Redis/RocketMQ/Milvus）；Testcontainers E2E IT；多环境变量 .env.example |

**含义**：B–J 是对既有 v1–v6 能力的体系化增强；新代码一律走 DDD 分层（domain 不依赖 infrastructure/spring/lombok），旧包（controller/service/entity）仅在必要处做接线适配，新能力不回退到旧分层。

---

## 2. 跨项目统一契约（本规格最重要的章节，各项目必须遵守）

### 2.1 统一产物模型（B 产生 → C 血缘 → D 规则 → E 引用 → G 展示）

```
KbDocument(1) ──< DocArtifact(版本化)
DocArtifact.kind ∈ RAW_FILE | PAGE | BLOCK | TABLE | FIELD_IMAGE | AUDIO_TRANSCRIPT | EXTRACTED_FIELD | CLEANED_TEXT | CHUNK_REF
DocArtifact { id, docId, versionNo, kind, pageNo?, bbox?, order?, contentRef(文件或内联), contentJson?, sha256, createdBy(SYSTEM/PROVIDER/RULE/HUMAN), provider?, model?, status(ACTIVE/SUPERSEDED) }
ProvenanceEdge { id, docId, fromArtifactId, toArtifactId, relation, ruleVersion?, confidence?, detail? }
relation ∈ DERIVED(清洗/切分) | EXTRACTED(OCR/ASR/字段提取) | STITCHED(跨页拼接) | RULED(规则计算) | EDITED(人工修改) | CITED(引用) | SUPERSEDES(版本)
ExtractedField { docId, versionNo, key, value, valueType, confidence(0-1), evidence:[{artifactId,pageNo,bbox,snippet}], source(MODEL/RULE/HUMAN), provider?, model?, ruleVersion?, status(ACTIVE/OVERRIDDEN) }
```

- 所有产物的大文本存派生文件 `data/uploads/{docId}/artifacts/`（DB 存 contentRef+sha256），延续 A「字节不入库不进 MQ」。
- **置信度**：B 字段提取与 OCR 必须给 0–1 confidence；阈值 `wikiagent.extract.low-confidence-threshold`（默认 0.75），低于阈值自动开人工复核（D）。
- **冲突标记**：OCR 文本与嵌入层文本差异率超阈值、跨页表格拼接歧义、多供应商结果不一致 → Artifact 标 `CONFLICT` + edge detail，不静默选一个。

### 2.2 能力 Provider SPI（B 定义，E 扩展到 LLM 供应商）

```java
// domain/parse/spi
interface DocumentAiProvider {
  String name();                              // dashscore | tencent | baidu | xfyun | none
  Set<Capability> capabilities();             // OCR, ASR, LAYOUT, TABLE
  boolean available();
  OcrResult ocr(OcrRequest r);                // 图片/扫描页 → 文本+bbox+置信度
  TranscriptResult transcribe(AudioRequest r);// 音频 → 分段文本(含时间轴)
  LayoutResult layout(PageRequest r);         // 版面块序列
  TableResult table(TableRequest r);          // 表格矩阵+单元格 bbox
}
```
- ProviderRegistry 按能力选择，配置 `wikiagent.parse.provider=dashscope`（首批实现；tencent/baidu/xfyun 仅留适配位与配置，不实现）；`none` 时跳过 AI 能力并在文档上标 `AI_SKIPPED`。
- 统一超时/重试/熔断：每能力独立超时（OCR 30s/ASR 120s/LAYOUT 30s/TABLE 60s 可配），瞬时错误重试 2 次指数退避，连续失败熔断 60s（沿用 Milvus 熔断模式）；**外部服务不可用不阻断文本类文档主流程**，扫描件/音频类则任务失败可重试。
- LLM 供应商（E）：`ChatModelProvider` SPI 包装现有 DashScopeMultiModelFactory，provider 切换不改业务代码。

### 2.3 规则引擎契约（D）

```
RuleSet { code, version, status(DRAFT/ACTIVE/ARCHIVED), dslJson, checksum }
RuleComputation { id, ruleCode, ruleVersion, inputSnapshot, intermediatesJson, outputJson, status, durationMs, computedAt }
```
- 规则执行**确定性、零 LLM**；同一 inputSnapshot+ruleVersion 必须得到同一 output（重算回放的判定依据）。
- 每个输出字段挂 RULED edge + ruleVersion；规则版本升级产生新 RuleSet 版本，不覆盖历史，可对旧文档按新版本重算并对比 diff。

### 2.4 人工复核工作流契约（B/D/F 共用 A 的 human_task）

- HumanTaskKind 在既有 INPUT 基础上新增：`REVIEW`（通过/驳回/编辑）、`TOOL_APPROVAL`（F 高危工具批准）、`DECRYPT`（B 加密文件口令）。
- 复核案件 D 建 review_case 聚合（字段级 diff、来源、置信度、初审结论）；人工提交后字段产生 `ExtractedField(status=OVERRIDDEN)` 新版本 + EDITED edge；task_event 全程留痕（actor=USER、旧值/新值）。

### 2.5 引用契约（C/E/G）

- 引用统一结构：`{docId, versionNo, pageNo, snippet, artifactId, score}`；Milvus metadata_json 必须含 docId/versionNo/pageNo/snippet 前 200 字/artifactId；答案中引用编号与来源列表一一对应，引用只能指向 is_active 或被答案快照固定的历史版本（可解析不 404）。

### 2.6 模型调用与成本契约（E 产生，I/H 消费）

- 新表 `model_call_log`：traceId, userId, sessionId, purpose(INTENT/EXTRACT/RERANK/CHAT/JUDGE…), provider, model, tokensIn/Out, costEstimate, latencyMs, status, fallbackFrom?, createdAt。
- 每次模型调用一行；agent_trace 的 llm_call span 与 model_call_log 以 traceId 关联。成本单价走配置表（可热更新），无单价时记 token 不计费。

### 2.7 API 与前端契约

- 所有新端点沿用 `/api/...`、`X-User-Id`（缺省 anonymous）、`X-Business-Identity`、错误体 `{timestamp,status,error,message}`；新增字段在 DTO 中以可选方式追加，不破坏 G 既有调用。
- 流式统一 SSE：`event: <name>\ndata: <json>`（delta/progress/done/error）。

---

## 3. 子项目需求与验收标准

### B 文档智能解析流水线（需求 2、11）

功能：①多 Provider SPI + DashScope 实现（OCR/ASR/版面/表格）；②格式扩展：扫描件 PDF、png/jpg/tiff 图片、mp3/wav/m4a 音频、加密 PDF（DECRYPT 人工口令）、超大文件（流式/分页，超限可配）、损坏文件明确 PARSE_FAILED、.doc/.xls 经 Apache Tika 兜底；③版面块/跨页表格拼接/表格矩阵；④结构化字段提取（按域 JSON Schema、结构化输出、校验 required/type/regex/enum、低置信转人工）；⑤INGEST 流水线扩展为条件步骤（PARSE 分流 TEXT/OCR/ASR → LAYOUT → EXTRACT → CLEAN → SPLIT → EMBED → VERIFY），全步骤 checkpoint 幂等；⑥解析产物与 provenance 落库（C 的表，B 写 EXTRACTED/STITCHED edge）；⑦失败重跑沿用 A 重试/重放，重复入库不重复处理。

- AC-B1（rule）：txt/md/pdf/docx/xlsx 既有 6 步行为零回归；扫描 PDF/图片走 OCR、音频走 ASR，产物含 pageNo/时间轴与置信度，证据：新增集成测试 + 固定样本。
- AC-B2（rule）：加密 PDF 触发 WAITING_HUMAN(DECRYPT)，提交口令后续跑成功；损坏文件以 PARSE_FAILED 终态（不重试死循环）；.doc/.xls 经 Tika 成功提取。证据：TaskWorker 风格集成测试。
- AC-B3（rule）：跨页表格产出单一 TableResult（行连续、页边界有 STITCHED edge）；文本/OCR 不一致产生 CONFLICT 标记而非静默；低置信字段自动建 REVIEW 人工任务。
- AC-B4（rule）：Provider 超时/抛错按 SPI 契约重试 2 次后熔断；OCR 不可用时纯文本文档仍 READY；同 docId 重放不产生重复 chunk/field（唯一约束+幂等查询）。
- AC-B5（rule）：每个 ExtractedField 可经 C 的 API 回溯到文件+页码+原文片段（artifactId 链完整）。
- AC-B6（rubric 1–5，≥4）：解析流水线可维护性——步骤声明式、Provider 新增仅需实现 SPI+配置（不改流水线代码）、异常分类完整。评审按锚点评分。

### C 数据血缘与版本（需求 3）

功能：V10 建表 doc_artifact/provenance_edge/extracted_field/field_version/doc_version；各产线写 edge；chunk metadata 扩展版本/页码/片段；知识重解析产生新版本（旧 chunk is_active=false，历史引用可解析）；血缘与版本查询 API + 版本 diff。

- AC-C1（rule）：给定 fieldKey，`GET /api/documents/{docId}/fields/{key}/lineage` 返回 RAW→EXTRACTED→(RULED)→(EDITED) 完整链，含文件、页码、原文片段、模型/规则版本、修改人。
- AC-C2（rule）：文档重新解析后版本号递增，旧版本 chunk 不被物理删除且按 versionNo 仍可取到来源；检索默认仅命中最新 ACTIVE 版本。
- AC-C3（rule）：`GET …/versions/{a}/diff/{b}` 输出字段级增删改（含人工修改项），测试覆盖连续 3 版本。
- AC-C4（rule）：Milvus 命中 chunk 可解析出 docId+pageNo+snippet（metadata 非空断言，E2E IT）。

### D 确定性规则引擎 + 人工复核（需求 6、14）

功能：规则集 CRUD+版本+发布；确定性引擎（DSL：字段映射/算术/正则/枚举/跨表对照/材料 A-B 比对），输入/中间/输出全留存；重算与跨版本回放；review_case 与 REVIEW 人工任务（通过/驳回/编辑、字段 diff、留痕）；MODEL/RULE/HUMAN 三类来源在 UI/API 显式区分。

- AC-D1（rule）：同一 inputSnapshot 连续执行同一 ruleVersion 100 次输出一致（重算测试）；升级规则版本后旧 computation 仍可查且可回放。
- AC-D2（rule）：材料对照产出差异清单（字段级），差异自动建复核案件；审批通过/驳回/编辑三动作均写 task_event 与 EDITED/审批 edge。
- AC-D3（rule）：规则引擎执行路径不发起任何 LLM 调用（静态检查+测试断言 model_call_log 无 RULED 目的记录）。
- AC-D4（rule）：REST 提供规则集发布、单文档重算、案件列表/处置、版本 diff 端点，全部有集成测试。

### E RAG / 模型治理增强（需求 4、12）

功能：Prompt 模板版本表+发布+渲染留痕；ChatModelProvider SPI（供应商切换/结构化输出/超时重试降级链）；Embedding 与答案缓存（Redis，可关、TTL 可配）；model_call_log 成本记账；检索元数据过滤+身份 ACL 表达式下推；RerankProvider SPI（DashScope rerank 首批）；引用按 §2.5 落页码/片段；知识更新/删除走版本+软删与重建索引；未命中策略保持。

- AC-E1（rule）：检索请求必须带权限表达式，越权身份无法命中受限 domain chunk（构造两个 domain 的 IT 断言）。
- AC-E2（rule）：Prompt 以 key+version 渲染，trace 记录实际版本；切换 ACTIVE 版本不改代码。
- AC-E3（rule）：每次聊天/提取/重排产生 model_call_log 行（token、耗时、状态、fallbackFrom）；Provider 故障自动降级到下一候选并在日志标记。
- AC-E4（rule）：答案每个引用编号可解析到 docId+versionNo+pageNo+snippet；删除文档后检索不再命中其 ACTIVE chunk。
- AC-E5（rubric 1–5，≥4）：RAG 治理完整度（重排有效提升、缓存命中率可观测、成本可核算）评审。

### F Agent 治理增强（需求 5、13）

功能：工具授权注册表（工具→所需角色/scope、每 Agent allowlist、拒绝审计）；任务级预算（token/成本/迭代次数上限，payload 可覆盖，超限策略 PAUSE_HUMAN|FAIL 可配）；上下文用量计量与压缩；高危工具 TOOL_APPROVAL 人工批准；固定流程/Agent/确定性程序/人工边界在代码与文档中显式（规则引擎只能以版本化工具被调用）。

- AC-F1（rule）：未授权身份调用受控工具被拒绝且写 audit_log(TOOL_DENIED)，测试覆盖 allowlist/角色/越权三类。
- AC-F2（rule）：任务超预算时在 ReAct 迭代边界停止并按策略转 WAITING_HUMAN 或 FAILED，事件含已用/上限；已用额度持久化（worker 重算不重置）。
- AC-F3（rule）：TOOL_APPROVAL 未批准前工具不执行，批准后续跑、驳回则跳过并记录；planner 不重复规划（沿用 A 的 plannerCalls==1 断言）。
- AC-F4（rubric 1–5，≥4）：四类边界（固定流程/Agent/规则/人工）在编排代码与文档中可被评审者明确指出。

### I 全链路可观测与韧性（需求 9）

功能：traceId MDC 贯穿 chat→retrieve→model→tool→task；新增积压/延迟/错误率/模型失败/费用/DB 连接池/第三方健康 Micrometer 指标与 Prometheus；限流（用户/租户令牌桶）、缓存、队列背压（对接 A tenant-max-concurrent）、统一熔断器注册表与降级矩阵；过载 shed。

- AC-I1（rule）：一次聊天请求的日志/trace/model_call_log/task_event 可用同一 traceId 串联（IT 断言）。
- AC-I2（rule）：`/actuator/prometheus` 含任务积压、模型调用总量/费用、第三方熔断状态指标（命名约定 wikiagent_*）。
- AC-I3（rule）：限流阈值可配，超限返回 429 并写审计；集成测试模拟突发验证。
- AC-I4（rubric 1–5，≥4）：降级矩阵覆盖 Milvus/Redis/RocketMQ/LLM/Provider 五类，文档与行为一致评审。

### J 运维与安全（需求 10）

功能：dev/staging/prod profile 隔离；CI 增加打包/镜像/部署工作流（生产部署手动闸门）；功能开关登记表+actuator 可见；备份回滚手册（MySQL dump/Flyway 修复、Milvus 快照说明、Redis 无状态）；输出流式网关校验；租户隔离测试；最小权限与 PII 最小化外发（Spotlighting 复用）；注入/越权/误调用三类攻击回归用例；X-User-Id 信任边界文档化（生产须由网关注入）。

- AC-J1（rule）：三套 profile 配置齐备且 prod 默认值安全（DDL=validate、fallback 关、debug 关），启动上下文测试验证。
- AC-J2（rule）：流式回答在结束前经输出网关校验（既有非流式路径不回退）；注入/越权/工具误调用回归用例全部通过。
- AC-J3（rule）：外发 LLM 的文本经过 PII/敏感字段处理（单测断言样例被脱敏或阻断）。
- AC-J4（rule）：备份与回滚步骤在文档中可照做（含命令），CI 工作流对生产环境有 manual approval 闸门。

### H 测试与模型评测体系（需求 8、15）

功能：固定黄金样本集（解析：扫描件/表格/加密/音频；检索：问题→期望 chunk；规则：输入→期望输出；异常路径）；离线评测运行器（扩展 eval/，`mvn -Peval` profile，无 API key 用录制固件打分）；七项指标（字段准确率/检索命中率/引用正确率/事实+结构错误率/人工修改率/响应时间/单次成本）从 model_call_log、metric_event、review_case 计算并产出 EvalReport；失败分类法与回归基线；CI 接单测+IT+eval 校验；真实 LLM 评测打 `@LiveLLM` tag 仅夜间手动运行。

- AC-H1（rule）：`mvn test` 全波次保持绿；`mvn verify -Peval` 产出 EvalReport JSON（七项指标齐全，固件可离线跑）。
- AC-H2（rule）：黄金样本四类齐备且每类 ≥5 条（音频可用短固件），检索样本含期望 chunkId 断言。
- AC-H3（rule）：七项指标均有可复现计算单测（给定造数→期望数值）。
- AC-H4（rubric 1–5，≥4）：样本与业务域（九大知识域）相关性评审。

### G React + Next.js 前端（需求 7）

功能：`frontend/` 独立 Next.js 14（App Router + TypeScript + Ant Design）；页面：身份设置（开发态 X-User-Id/Identity）、材料上传（拖拽/多文件/域参数/重复提示）、任务列表与详情（SSE 步骤进度/事件/错误重试）、对话（SSE+引用角标）、结构化结果（字段表/置信度色标/冲突标记）、来源查看（页码+片段）、人工工作台（任务认领/REVIEW 通过驳回编辑/TOOL_APPROVAL/DECRYPT）、历史版本与 diff。类型化 API Client；开发代理到 8080；Dockerfile 并入 compose。

- AC-G1（rule）：浏览器可完成「上传→看进度→看字段结果→点开来源页码片段→低置信字段进入人工修改→提交确认→查看历史版本」全链路（Playwright e2e，后端用录制/测试环境）。
- AC-G2（rule）：API Client 类型由后端 DTO 契约生成或手工对齐且无 any 兜底泛滥（lint 门禁）；SSE 断线有重连/提示。
- AC-G3（rubric 1–5，≥4）：可用性（中文文案、加载/空/错三态、桌面端布局）评审。

---

## 4. 非功能约束（全波次硬约束）

1. DDD 新包分层；domain 层禁止 infrastructure/spring/lombok 依赖（每波复查 grep）。
2. 新表走 Flyway V10+，MySQL 为真相源，Redis 只协调，MQ 只投递；新增开关一律 `@ConditionalOnProperty` 且默认行为对既有用户安全（新功能默认开但无外部依赖时可退化，如 provider=none）。
3. 每波结束：`mvn test` 全绿（只增不减）、`mvn verify -Dtest.profile=light` 通过；Testcontainers IT 只在 CI 验证（本机无 Docker）。
4. Conventional Commits 中文提交；每波独立 review（fresh context 只读复查），问题回流 tasks.md 修复后再复查。
5. 外部服务（DashScope OCR/ASR/rerank）测试全部走接口桩+录制固件，CI 不依赖真实 key；真实调用冒烟由手工清单承载。
6. 不改动 A 已冻结的任务框架契约（状态机迁移表、task_step 编号规则、human_task 锁语义）；扩展走新增 kind/字段可选追加。
7. 后端服务保持 Java 21/Spring Boot 3，不引入需本机 Docker/GPU 的依赖；Tika 为纯 Java 例外允许。

## 5. 波次划分

| 波次 | 项目 | 理由 |
|---|---|---|
| 波 1 | B + C | C 的血缘/版本模型是 B 产物的落处；同批设计避免表结构返工 |
| 波 2 | D + E + F | 规则消费字段(C/B)，RAG 引用消费血缘，Agent 预算复用 model_call_log |
| 波 3 | I + J + H | 横切能力在功能齐备后统一铺；评测样本针对已稳定功能 |
| 波 4 | G | API 全部定稿后一次成型，减少前后端返工 |
| 每波末 | 独立复查 + 全量测试 + 手工清单更新 | review fail → 修复队列 → fresh review |

## 6. 假设与开放问题

1. DashScope OCR/多模态走 qwen-vl 系列、ASR 走语音转写文件接口；具体 API 在 B 实施时验证，失败则保持 SPI 与 none 降级（不阻塞波次合并）。
2. 生产认证沿用「网关注入 X-User-Id」假设，B–J 不做账号体系；J 只补共享密钥的开发态可选过滤器。
3. 前端部署形态：compose 内独立 node 服务 + nginx 反代 `/api`；无 SSR 数据敏感需求，用客户端渲染。
4. 音频/超大文件大小上限默认 200MB（对齐 multipart 配置），可配。
5. 规则 DSL 首批支持 JSON 声明式算子（map/arith/regex/enum/compare），不引入脚本引擎（安全）。
6. 若某波发现规格层冲突，回到本文件修订并在 tasks.md 留痕，不临时扩大范围。

## 7. 验收总地图

| 需求条 | 主 AC |
|---|---|
| 2、11 | AC-B1..B6 |
| 3 | AC-C1..C4 |
| 6、14 | AC-D1..D4 |
| 4、12 | AC-E1..E5 |
| 5、13 | AC-F1..F4 |
| 9 | AC-I1..I4 |
| 10 | AC-J1..J4 |
| 8、15 | AC-H1..H4 |
| 7 | AC-G1..G3 |
