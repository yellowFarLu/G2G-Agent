package com.wikiagent.domain.eval.anomaly;

/**
 * 异常路径场景集（anomaly-golden.json scenario 取值，规格 §H AC-H2）。
 */
public enum AnomalyScenario {
    /** 加密 PDF 缺口令/错口令 → DECRYPT 人工接管。 */
    ENCRYPTED,
    /** 损坏文件 → PARSE_FAILED（致命不重试）。 */
    CORRUPT,
    /** 超过大小上限 → VALIDATION_FAILED。 */
    OVERSIZE,
    /** 抽取字段低置信/校验失败 → REVIEW 人工复核。 */
    LOW_CONFIDENCE,
    /** 人工复核驳回 → 恢复流水线（不新建人工任务）。 */
    REVIEW_REJECT
}
