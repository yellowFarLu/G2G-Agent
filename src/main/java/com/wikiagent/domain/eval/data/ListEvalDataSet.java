package com.wikiagent.domain.eval.data;

import java.util.List;

/**
 * 基于内存 List 的 {@link EvalDataSource} 实现：供计算器单元测试与
 * 小规模回放使用。构造参数为 {@code null} 的类目按空列表处理。
 */
public record ListEvalDataSet(List<FieldSample> fields,
                              List<RetrievalSample> retrievals,
                              List<CitationSample> citations,
                              List<JudgeVerdict> judgeVerdicts,
                              List<ReviewDisposition> reviewDispositions,
                              List<ModelCallSample> modelCalls) implements EvalDataSource {

    public ListEvalDataSet {
        fields = fields == null ? List.of() : fields;
        retrievals = retrievals == null ? List.of() : retrievals;
        citations = citations == null ? List.of() : citations;
        judgeVerdicts = judgeVerdicts == null ? List.of() : judgeVerdicts;
        reviewDispositions = reviewDispositions == null ? List.of() : reviewDispositions;
        modelCalls = modelCalls == null ? List.of() : modelCalls;
    }

    /** 全类目为空的数据集（触发计算器 missing 语义）。 */
    public static ListEvalDataSet empty() {
        return new ListEvalDataSet(null, null, null, null, null, null);
    }
}
