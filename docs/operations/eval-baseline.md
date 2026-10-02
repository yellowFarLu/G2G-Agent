# 离线评测基线（子项目 H）

> 对应规格 `docs/superpowers/specs/2026-10-02-bj-program-design.md` §H。
> 离线评测在零真实 API key、零 Docker 条件下运行：检索走本地关键词降级，
> LLM/OCR/ASR/TABLE 全部使用录制桩或确定性引擎，真实 LLM 烟雾测试
> （`@Tag("LiveLLM")`）默认排除。

## 1. 如何运行

```bash
# 本地/CI：全量单测 + 评测套件（不跑其它 *IT，不需要 Docker）
mvn -B test -Peval

# 真实 LLM 烟雾测试（仅夜间/发布前人工，需先 export DASHSCOPE_API_KEY）
mvn test -Peval -Dtest=LiveLlmSmokeTest -Dsurefire.excludedGroups=

# 或启动应用后调用接口
curl -X POST http://localhost:8080/api/eval/run
```

运行后产出 `target/eval-report/EvalReport.json`（另留带时间戳的历史副本），
查询接口：`GET /api/eval/report/latest`。CI 夜间工作流
`.github/workflows/eval.yml`（每天 23:00 UTC）自动上传该文件为 artifact。

## 2. 黄金样本规模

| 套件 | 文件 | 条数 | 有效条数 |
| --- | --- | --- | --- |
| PARSE | `src/test/resources/eval/parse-golden.json` | 8 | 7（1 条真实 TIFF 固件缺失，按 skip 计） |
| RETRIEVE | `retrieve-golden.json` + `seed/retrieve-seed.json` | 6 | 6 |
| RULE | `rule-golden.json` + `stubs/rules/*.json` | 7 | 7 |
| ANOMALY | `anomaly-golden.json` | 6 | 6 |
| **合计** | | **27** | **26** |

## 3. 七项指标定义、数据源与计算窗口

计算窗口：`EvalWindow.since(运行开始时刻 - 2 秒)`，闭区间，覆盖本次播种与
套件执行期间产生的全部数据行；窗口外的历史数据不参与计算。数据通过
`JpaEvalDataSource` 从各仓储只读投影，评测不改业务写路径。

| 指标键 | 定义 | 数据源 |
| --- | --- | --- |
| `fieldAccuracyRate` | `valid=true` 字段记录数 / 全部字段抽取记录数 | 字段抽取结果表（种子 5 行：4 有效 / 1 无效） |
| `retrievalHitRate` | 带相关性标签且命中的 RETRIEVED 事件 / 全部带标签事件（relevant 为 null 不进分母） | metric_event RETRIEVED + kb_feedback（仅采纳 `eval-doc` 前缀种子反馈，避免共享库历史反馈污染） |
| `citationCorrectRate` | 引用 `resolvable && consistent` 的比例。resolvable=目标 chunk 存在且版本号匹配；consistent=pageNo 相等且 snippet 文本包含于 chunk 内容 | RETRIEVE 套件运行期内存收集的 CitationSample |
| `judgeErrorRates`（复合） | `factErrorRate` = 事实错误案数 / 评判案数；`structureErrorRate` 同口径（结构错误） | `stubs/judge/*.json` 4 条录制评判（fact-error/structure-error/both-errors/clean） |
| `humanEditRate` | `EDITED / (APPROVED + REJECTED + EDITED)`；OPEN 未处置案件不计 | 人工复核案件表（种子 APPROVED/REJECTED/EDITED×2/OPEN） |
| `responseTimeP50P95`（复合） | `p50`/`p95` 延迟毫秒（最近秩法 rank=ceil(p/100×n)）+ `sampleCount`；仅 status=OK 且 latencyMs 非空 | model_call_log（种子 50/100/200/300/400ms） |
| `costPerTurn` | 全 purpose 成本估算之和 / CHAT 调用行数；CHAT 行数为 0 时记 missing | model_call_log（种子 CHAT 4 行合计 0.105，RERANK 0.005 计入分子） |

指标无足够数据时取值 `missing`（value=null + note 说明），报告的
`baseline.status` 在首次 CI 夜间运行前固定为
`PENDING_FIRST_CI_NIGHTLY_RUN`。

## 4. 基线数值

**待首次 CI 夜间运行填充，禁止编造数值。**

首次夜间运行成功后，以该次 artifact 中七项指标为基线，按下表登记，并注明
runId / 日期 / git 提交：

| 指标 | 首次基线 | 阈值（环比告警） | 登记日期 / runId |
| --- | --- | --- | --- |
| fieldAccuracyRate | 待填充 | 待首次基线后约定 | — |
| retrievalHitRate | 待填充 | 待约定 | — |
| citationCorrectRate | 待填充 | 待约定 | — |
| judgeErrorRates.factErrorRate | 待填充 | 待约定 | — |
| judgeErrorRates.structureErrorRate | 待填充 | 待约定 | — |
| humanEditRate | 待填充 | 待约定（上升为劣化） | — |
| responseTimeP50P95.p50 / p95 | 待填充 | 待约定（上升为劣化） | — |
| costPerTurn | 待填充 | 待约定（上升为劣化） | — |

## 5. 失败分类 taxonomy

样本失败与夜间流水线红灯统一归入以下六类，报告中的 `error` 字段与人工
复盘均沿用该分类命名：

1. **parse**：解析类（文本层抽取、表格跨页拼接、页数、加密/损坏/超大等异常态判定）失败。
2. **retrieve**：黄金 chunk 未召回，或 forbidden chunk 被召回；检索命中率劣化。
3. **citation**：引用不可解析（chunk/版本失配）或不一致（页码、snippet 失配）。
4. **judge**：事实/结构错误录制评判与预期不符（校验器/提示词回归）。
5. **rule**：确定性规则 DSL 执行结果与黄金期望不符（规则引擎回归）。
6. **infra**：评测基础设施问题（种子落库、上下文启动、文件读写、报告产出），非业务质量退化。
