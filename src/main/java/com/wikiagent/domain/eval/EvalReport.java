package com.wikiagent.domain.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线评测聚合报告（序列化为 target/eval-report/EvalReport.json）。
 *
 * @param generatedAt  报告生成时间（ISO-8601 字符串，避免域层依赖 Jackson 时间模块）
 * @param runId         评测运行 ID（时间窗/造数隔离用）
 * @param gitInfo       Git 信息（CI 环境变量注入；本地离线可 null）
 * @param profile       运行 profile（offline 录制固件 / live 真实 LLM，夜间手动）
 * @param summary       七项指标（键定义见 docs/operations/eval-baseline.md）
 * @param sampleResults 全量样本结果（parse/retrieve/rule/anomaly）
 * @param baseline      回归基线对照（首次 CI 夜间运行前为占位，禁止编造数值）
 */
public record EvalReport(String generatedAt,
                         String runId,
                         GitInfo gitInfo,
                         String profile,
                         Map<String, MetricValue> summary,
                         List<SampleResult> sampleResults,
                         Map<String, Object> baseline) {

    public EvalReport {
        summary = summary == null ? Map.of() : new LinkedHashMap<>(summary);
        sampleResults = sampleResults == null ? List.of() : List.copyOf(sampleResults);
        baseline = baseline == null ? Map.of() : new LinkedHashMap<>(baseline);
    }

    /** Git 来源信息（全可 null：本地离线运行无 CI 环境变量）。 */
    public record GitInfo(String commit, String ref, String workflowRun) {
    }
}
