package com.wikiagent.application.eval.suite;

import com.wikiagent.domain.eval.SampleResult;
import com.wikiagent.domain.eval.data.CitationSample;

import java.util.List;

/**
 * 单个评测类别的产出：样本结果 + 该类别运行中累积的引用判定（仅 retrieve 非空）。
 */
public record SuiteOutput(List<SampleResult> results, List<CitationSample> citations) {

    public SuiteOutput {
        results = results == null ? List.of() : List.copyOf(results);
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    public static SuiteOutput of(List<SampleResult> results) {
        return new SuiteOutput(results, List.of());
    }
}
