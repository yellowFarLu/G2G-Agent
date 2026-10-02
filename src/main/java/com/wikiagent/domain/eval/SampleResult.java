package com.wikiagent.domain.eval;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单条黄金样本评测结果。
 *
 * @param id       黄金样本 id
 * @param category 样本类别
 * @param passed   是否通过全部断言
 * @param score    0.0~1.0 的部分得分（断言项通过率；硬失败为 0.0）
 * @param skipped  是否跳过（缺夹具/缺能力；跳过样本不计入有效条数）
 * @param metrics  样本级度量（如 matched/total、耗时、命中清单）
 * @param error    执行异常信息（评测器自身故障，分类为 infra）；正常为 null
 */
public record SampleResult(String id,
                           EvalCategory category,
                           boolean passed,
                           double score,
                           boolean skipped,
                           Map<String, Object> metrics,
                           String error) {

    public SampleResult {
        metrics = metrics == null ? Map.of() : new LinkedHashMap<>(metrics);
    }

    public static SampleResult passed(String id, EvalCategory category, double score,
                                      Map<String, Object> metrics) {
        return new SampleResult(id, category, true, score, false, metrics, null);
    }

    public static SampleResult failed(String id, EvalCategory category, double score,
                                      Map<String, Object> metrics, String reason) {
        return new SampleResult(id, category, false, score, false, metrics, reason);
    }

    public static SampleResult error(String id, EvalCategory category, String error) {
        return new SampleResult(id, category, false, 0.0, false, Map.of(), error);
    }

    public static SampleResult skipped(String id, EvalCategory category, String reason) {
        return new SampleResult(id, category, false, 0.0, true,
                Map.of("skipReason", reason == null ? "" : reason), null);
    }
}
