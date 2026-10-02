package com.wikiagent.domain.eval;

/**
 * 离线评测四大黄金样本类别（规格 §H / AC-H2）。
 */
public enum EvalCategory {
    /** 文档解析：文本 PDF/扫描 OCR/图片 OCR/加密口令/录音 ASR/跨页表格/旧版 .doc。 */
    PARSE,
    /** 混合检索：问题 → 期望 chunk（H2 种子库 + 本地关键词降级路径，零 Milvus）。 */
    RETRIEVE,
    /** 确定性规则引擎：DSL 输入 → 期望输出（七算子，零 LLM）。 */
    RULE,
    /** 异常路径：加密/损坏/超大/低置信/人工驳回。 */
    ANOMALY
}
