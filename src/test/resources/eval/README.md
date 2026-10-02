# 离线评测固件目录（子项目 H，AC-H1..H4）

本目录全部资源用于**离线评测**：零真实 API key、零 Docker/Milvus 依赖。
真实 LLM 评测仅存在于 `LiveLlmSmokeTest`（`@Tag("LiveLLM")`，默认跳过，夜间手动）。

## 分文件结构

| 文件 | 类别 | 有效条数 | 说明 |
| --- | --- | --- | --- |
| `parse-golden.json` | 解析 | 7 有效 + 1 skip | 文本 PDF / 扫描 OCR / 加密口令 / 跨页表格 / 图片 OCR / 音频 ASR / .doc Tika |
| `retrieve-golden.json` | 检索 | 6 | 问题 → 期望 chunkId / 禁止 chunkId，对应 `seed/retrieve-seed.json` |
| `rule-golden.json` | 规则 | 7 | 七算子 map/const/arith/regex/enum/compare/materialDiff |
| `anomaly-golden.json` | 异常 | 6 | ENCRYPTED×2 / CORRUPT / OVERSIZE / LOW_CONFIDENCE / REVIEW_REJECT |
| `golden.json` | 遗留 | 10 | v1-v2 §9 路由/Judge 用例，**保留兼容** `AgentEvalTest`，不属于 H 四类 |

业务域覆盖（AC-H4，九大域 DomainTag code）：parse+retrieve 合计覆盖
`industry_solution / pms / customs / settlement / merchant_center / trajectory` 六个域（≥4）。

## JSON Schema

### parse-golden.json 元素

```json
{
  "id": "parse-text-pdf-customs",
  "kind": "pdf-text | pdf-scan-ocr | pdf-encrypted | pdf-cross-page-table | image-ocr | audio-asr | doc-tika",
  "domain": "customs",
  "fixturePath": "classpath:fixtures/parse/legacy.doc | synthetic://pdf/invoice-text",
  "password": "加密 PDF 正确口令，可空",
  "expects": {
    "textContains": ["全文必须全部包含的字符串"],
    "tableRows": 3,
    "pageCount": 2,
    "fields": {"invoiceNo": "值须出现在全文中"}
  },
  "skipReason": "非空=跳过（缺夹具，不二进制新建），不计入有效条数"
}
```

`fixturePath` 两种来源：

- `classpath:`：读取已提交的二进制夹具（当前仅 `fixtures/parse/legacy.doc`）。
- `synthetic://`：评测运行时用 PDFBox / ImageIO / POI **确定性生成**的内存字节
  （与 `RichDocumentParserTest` 同一做法），不是提交的二进制文件：
  - `pdf/invoice-text` 文本层 PDF；`pdf/blank-scan` 空白扫描 PDF（桩 OCR）；
    `pdf/encrypted` 口令 `eval-secret` 的加密 PDF；`pdf/table-2pages` 两页续表 PDF（桩 LAYOUT/TABLE）；
  - `image/png` 4×4 PNG（桩 OCR）；`audio/mp3` 3 字节合成音频（桩 ASR）。
- OCR/ASR/LAYOUT/TABLE 全部走 `OfflineEvalAiProvider` 录制桩，不触达任何真实供应商。

### retrieve-golden.json 元素

```json
{
  "id": "...",
  "query": "自然语言问题",
  "domain": "settlement",
  "expectedChunkIds": ["eval-c-settlement-1"],
  "forbiddenChunkIds": ["eval-c-industry-old"]
}
```

期望/禁止 chunkId 必须存在于 `seed/retrieve-seed.json`。运行器先播种再经
`RetrievalService` 本地关键词降级路径（桩 EmbeddingModel 恒抛错触发，零 Milvus）检索；
现网 `Source` 仅暴露 docId，断言在 chunk→doc 映射粒度进行（映射由种子库解析）。

### rule-golden.json 元素

```json
{
  "id": "...",
  "ruleCode": "eval-rule-arith",
  "domain": "customs",
  "dslFixture": "stubs/rules/rule-arith.json",
  "input": {"unitPrice": 100, "qty": 3},
  "expectedOutput": {"totalAmount": 300}
}
```

`dslFixture` 为 `DeterministicRuleEngine` 的 `{steps, outputs}` 程序（零 LLM）。
数值经规范化比对（300(int) 与 300(long) 视为一致）。

### anomaly-golden.json 元素

```json
{
  "id": "...",
  "scenario": "ENCRYPTED | CORRUPT | OVERSIZE | LOW_CONFIDENCE | REVIEW_REJECT",
  "domain": "...",
  "expects": {"taskStatus": "...", "documentStatus": "...", "humanKind": "...", "errorCode": "..."},
  "note": "场景说明"
}
```

只断言 `expects` 中出现的键。处置映射由 domain 层 `AnomalyClassifier` 编码，
与生产 `IngestTaskHandler` catch 契约一致；异常信号本身由真实组件产生
（RichDocumentParser / ParseInputValidator / FieldExtractionService + 录制抽取固件）。

## seed/retrieve-seed.json

评测造数（documents/parents/children/metadata/feedback/fields/reviewCases/modelCalls）。
- 全部 id 以 `eval-` 前缀标识，运行器幂等“先按前缀删除再插入”；
- 时间字段统一用字符串 `"NOW"`，加载时解析为 `Instant.now()`，落入本次运行时间窗；
- kb_feedback 的 chunkId 与 metric_event 保持现网口径一致（存 docId，见
  `RetrievalService#recordRetrievalMetrics` 的粒度声明）。

## stubs/

- `rules/*.json`：七算子 DSL 程序夹具；
- `judge/*.json`：录制的“问题+答案→评判结果”（factError/structureError），
  供 factErrorRate / structureErrorRate 离线计算。
