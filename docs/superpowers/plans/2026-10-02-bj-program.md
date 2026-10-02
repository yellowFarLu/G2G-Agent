# B–J 总任务队列（分波实施）

配套规格：`docs/superpowers/specs/2026-10-02-bj-program-design.md`
执行规则：每波按本队列原子任务顺序实施（TDD：测试先行或与实现同提交）；每任务完成必须有 Completion Evidence（测试类/命令/截图路径）；波末做 fresh-context 独立复查，fail 项回流本文件「复查问题」段，修复后再复查，pass 才进入下一波。

任务状态字段：pending / in_progress / completed（只在任务标题行用注释标注 `[x]`，证据写在任务下）。

---

## 波次 1：B 解析流水线 + C 血缘版本（最高优先级）

### B-1 SPI 与 Provider 基础设施
- [x] B1.1 `domain/parse/spi`：DocumentAiProvider 接口 + Capability + 请求/结果记录（OcrResult 含 bbox/confidence，TranscriptResult 含时间轴，LayoutResult 有序块，TableResult 矩阵）
- [x] B1.2 ProviderRegistry（按能力选 provider、wikiagent.parse.provider 配置、none 实现、available 探测）
- [x] B1.3 统一调用策略：超时/2 次指数退避/60s 熔断（独立 circuit state，仿 Milvus 模式）+ ParseProviderException 异常分层
- [x] B1.4 DashScopeProvider 实现（qwen-vl OCR/版面/表格 + 语音文件转写）；无 key 时 available=false（不阻断上下文启动）
- [x] TR：SPI 契约测试（fake provider 注册/选择/熔断/降级）；DashScope 走 WireMock 风格桩（不真实联网）

### B-2 格式与异常覆盖
- [x] B2.1 引入 Apache Tika 兜底 .doc/.xls（纯 Java）；DocumentParser 重构为「原生解析器优先+Tika 兜底」
- [x] B2.2 图片格式（png/jpg/tiff）→ OCR 路径；扫描件 PDF 检测（页文本为空/极少 → 整页 OCR）
- [x] B2.3 音频（mp3/wav/m4a）→ ASR 路径（转录文本+时间轴 artifact）
- [x] B2.4 加密 PDF：检测 → HumanRequiredException(DECRYPT, 需要口令) → 口令续跑（错误口令可重试 3 次）
- [x] B2.5 损坏文件/超大文件：明确 PARSE_FAILED 不重试；超大按页流式（上限 wikiagent.parse.max-file-mb 默认 200）
- [x] TR：每类格式一个固定样本（src/test/resources/fixtures/parse/，音频用 1-2s 静音短固件）；加密/损坏用 PDFBox 测试生成

### B-3 版面/表格/冲突
- [x] B3.1 PageBlock 模型（pageNo/bbox/order/type）落 artifact（B 写、C 表在 B-6）
- [x] B3.2 跨页表格拼接（同名表头续接判定+STITCHED edge+歧义 CONFLICT 标记）
- [x] B3.3 文本层 vs OCR 差异率检测 → CONFLICT artifact
- [x] TR：跨页表格合成 PDF 样本断言行数连续；差异样例断言 CONFLICT

### B-4 结构化字段提取
- [x] B4.1 域 ExtractionSchema 注册表（JSON Schema：required/type/regex/enum，先落 1 个示例域 schema）
- [x] B4.2 LLM 结构化提取（ChatModel + JSON schema 输出+修复重试 1 次），产出 ExtractedField(confidence/evidence)
- [x] B4.3 字段校验器 + 低置信/校验失败 → HumanRequiredException(REVIEW)（阈值可配）
- [x] TR：用录制的 LLM 响应固件断言字段映射、校验失败转人工、置信度透传

### B-5 INGEST 流水线扩展（不破坏 A 契约）
- [x] B5.1 planSteps 按文件类型条件化（TEXT 六步 / OCR+LAYOUT+EXTRACT / ASR+EXTRACT），动态步骤注册沿用 AgentTaskHandler 模式（大编号段 100+）
- [x] B5.2 每步 checkpoint 幂等（artifact 已存在跳过、field 唯一约束、CONFLICT 不阻断 READY）
- [x] B5.3 文档状态扩展（KbDocument 增 EXTRACTING/AI_SKIPPED 可选态，旧状态不变）
- [x] TR：TaskWorker 集成测试覆盖三条分流 + 断点重跑不重复；既有 IngestTaskHandlerTest 零回归

### C-1 血缘与版本数据模型（V10）
- [x] C1.1 V10 迁移：doc_artifact / provenance_edge / extracted_field / field_version / doc_version（唯一约束、索引、外键软关联）
- [x] C1.2 domain 模型 + JPA 实体/DAO；ArtifactStore（派生文件落 `data/uploads/{docId}/artifacts/`，sha256 校验）
- [x] C1.3 ProvenanceService（写 edge、版本递增、SUPERSEDES 链）
- [x] TR：V10 在 H2 MODE=MySQL 集成测试真实执行；版本链/唯一约束测试

### C-2 产线接线与 chunk 溯源
- [x] C2.1 B 各步骤写 edge（DERIVED/EXTRACTED/STITCHED）；D 预留 RULED 常量
- [x] C2.2 knowledge_metadata 与 Milvus metadata_json 扩展 versionNo/pageNo/snippet/artifactId（回填默认值，旧数据不破坏）
- [x] C2.3 重解析新版本：旧 chunk is_active=false + doc_version 行；检索默认仅 ACTIVE
- [x] TR：IT 断言 chunk→docId/pageNo/snippet 可解析；重解析两版本隔离

### C-3 血缘/版本 API
- [x] C3.1 `GET /api/documents/{docId}/lineage`（产物树+edge）
- [x] C3.2 `GET /api/documents/{docId}/fields/{key}/lineage`（字段完整链）
- [x] C3.3 `GET /api/documents/{docId}/versions` + `…/versions/{a}/diff/{b}` 字段级 diff
- [x] TR：MockMvc 集成测试（3 版本 diff 增删改全覆盖）

### 波 1 闸门
- [x] 全量 `mvn test` 绿（424 项，含新增 42 项）、light verify 通过 → fresh review（对照 AC-B1..B6、AC-C1..C4）→ 修订（6 项缺陷修复）→ 提交波次总结（commit b39c5c9）

---

## 波次 2：D 规则引擎/复核 + E RAG 治理 + F Agent 治理

### D（AC-D1..D4）
- [ ] D1 V11 rule_set/rule_computation/review_case 表；规则版本生命周期（DRAFT/ACTIVE/ARCHIVED+checksum）
- [ ] D2 JSON 声明式算子引擎（map/arith/regex/enum/compare/材料对照），input/intermediates/output 全留存
- [ ] D3 重算/跨版本回放 API；规则升级 diff
- [ ] D4 review_case + REVIEW 人工任务接线（通过/驳回/编辑→field_version+EDITED edge+task_event）
- [ ] D5 REST：规则集 CRUD/发布、重算、案件列表/处置；MODEL/RULE/HUMAN 来源标记
- TR：100 次确定性重算一致测试；零 LLM 调用断言；三类处置留痕测试；材料对照差异造数测试

### E（AC-E1..E5）
- [ ] E1 V12 prompt_template/model_call_log 表；PromptTemplateService（key+version+ACTIVE 渲染，PromptComposer 接线）
- [ ] E2 ChatModelProvider SPI + DashScope 适配（包装既有工厂）；结构化输出/超时/重试/降级链；RerankProvider SPI + DashScope rerank
- [ ] E3 Redis 缓存（embedding 精确缓存 + 答案缓存策略，开关/TTL/bypass）
- [ ] E4 model_call_log 全链路打点（INTENT/EXTRACT/CHAT/RERANK/JUDGE，含 token/成本/耗时/fallbackFrom）+ 单价配置
- [ ] E5 检索权限：domain/subDomain/identity 表达式下推 Milvus；引用契约 §2.5（pageNo/snippet/versionNo）
- [ ] E6 知识更新/删除：版本化重建索引、软删传播到检索；冲突决议与版本联动
- TR：越权 domain 隔离 IT；provider 降级桩测试；缓存命中/失效测试；引用完整性测试；打点计数测试

### F（AC-F1..F4）
- [ ] F1 ToolPermissionRegistry（工具→角色/scope + 每 Agent allowlist），调用前拦截+TOOL_DENIED 审计
- [ ] F2 任务预算（token/cost/maxIterations，payload 覆盖+系统上限封顶），ReAct 迭代边界计量与超限策略（PAUSE_HUMAN/FAIL），额度持久化 task payload/step checkpoint
- [ ] F3 上下文用量计量+压缩；TOOL_APPROVAL 人工任务（批准/驳回/超时）
- [ ] F4 四类边界文档化+代码标记（固定流程 handler / Agent / 规则版本化工具 / 人工）
- TR：三类越权拒绝测试；预算超限两策略测试；批准续跑/驳回跳过测试（沿用 blockFirstReAct 模式）；plannerCalls==1 保持

### 波 2 闸门
全量测试 → fresh review（D/E/F 全部 AC + 边界 rubric）→ 修订 → 提交

---

## 波次 3：I 可观测韧性 + J 运维安全 + H 评测体系

### I（AC-I1..I4）
- [ ] I1 traceId MDC 贯穿（chat/retrieval/model/tool/task），model_call_log/agent_trace/task_event 关联
- [ ] I2 Micrometer 指标：任务积压、延迟直方图、错误率、模型失败、费用、DB 池、第三方健康/熔断（wikiagent_* 命名）
- [ ] I3 令牌桶限流（用户/租户，429+审计）；队列背压联动 tenant-max-concurrent；统一熔断器注册表
- [ ] I4 降级矩阵文档（Milvus/Redis/RocketMQ/LLM/Provider）+ 逐项行为测试

### J（AC-J1..J4）
- [ ] J1 dev/staging/prod profiles（prod 安全默认）；CI 增加 package/image/deploy workflow（prod manual gate）
- [ ] J2 功能开关登记表 + /actuator/env 可见说明；备份回滚手册（MySQL/Flyway/Milvus/Redis）
- [ ] J3 输出流式网关（SSE 完成前校验，复用 GuardrailDetector）；输入网关回归
- [ ] J4 注入/越权/工具误调用三类攻击回归用例；PII 最小化外发断言；X-User-Id 信任边界文档+开发态共享密钥可选过滤器；租户隔离测试

### H（AC-H1..H4）
- [ ] H1 黄金样本集：parse / retrieve / rule / anomaly 四类各 ≥5（resources/eval）
- [ ] H2 离线评测运行器：`-Peval` profile + 录制固件（无 key 可跑）；LiveLLM tag 夜间手动
- [ ] H3 七项指标计算器（造数单测）+ EvalReport JSON + 查询 API
- [ ] H4 失败分类法与回归基线文档；CI 加 eval 校验 job

### 波 3 闸门
全量测试 + eval 报告 → fresh review → 修订 → 提交

---

## 波次 4：G Next.js 前端（AC-G1..G3）

- [ ] G1 frontend/ 脚手架（Next.js14 App Router + TS + Ant Design + SSE client + API 类型）；dev proxy 8080；Dockerfile+compose
- [ ] G2 身份设置 + 材料上传（拖拽/多文件/域/重复提示）
- [ ] G3 任务中心（列表/详情/步骤 SSE 进度/事件/重试）
- [ ] G4 对话页（SSE+引用角标+来源弹层）
- [ ] G5 结构化结果（字段表/置信度/冲突）+ 来源查看（页码/片段）
- [ ] G6 人工工作台（REVIEW/TOOL_APPROVAL/DECRYPT 队列与处置）+ 历史版本/diff
- [ ] G7 Playwright 全链路 e2e（上传→进度→结果→来源→修改→确认→历史）；lint 门禁
- 闸门：e2e 通过 → fresh review（含可用性 rubric）→ 提交 → 全项目手工执行清单终版 + 合并建议

---

## 复查问题登记（每波 review 追加）

（波次实施后由 fresh reviewer 填写：问题编号 / 对应 AC / 严重度 / 修复提交 / 复查结果）
