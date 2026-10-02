package com.wikiagent.domain.eval.metrics;

import com.wikiagent.domain.eval.EvalWindow;
import com.wikiagent.domain.eval.MetricValue;
import com.wikiagent.domain.eval.data.CitationSample;
import com.wikiagent.domain.eval.data.EvalDataSource;

/**
 * 3) citationCorrectRate 引用正确率。
 * <p>
 * 数据源：评测运行器在 retrieve 类样本上对每个引用直接判定
 * （docId/versionNo 可解析到有效 chunk，且 pageNo/snippet 与来源 chunk 一致）。
 * 正确 = resolvable AND consistent。无引用行 → missing。
 */
public final class CitationCorrectCalculator implements MetricCalculator {

    public static final String KEY = "citationCorrectRate";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public MetricValue compute(EvalDataSource data, EvalWindow window) {
        long total = 0;
        long correct = 0;
        for (CitationSample c : data.citations()) {
            if (!window.contains(c.at())) {
                continue;
            }
            total++;
            if (c.resolvable() && c.consistent()) {
                correct++;
            }
        }
        if (total == 0) {
            return MetricValue.missing(KEY, "窗口内无引用判定行（retrieve 样本未产出引用）");
        }
        return MetricValue.of(KEY, (double) correct / total);
    }
}
