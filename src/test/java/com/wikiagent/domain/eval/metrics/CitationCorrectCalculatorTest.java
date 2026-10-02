package com.wikiagent.domain.eval.metrics;

import com.wikiagent.domain.eval.EvalWindow;
import com.wikiagent.domain.eval.MetricValue;
import com.wikiagent.domain.eval.data.CitationSample;
import com.wikiagent.domain.eval.data.ListEvalDataSet;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CitationCorrectCalculatorTest {

    private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");
    private final CitationCorrectCalculator calc = new CitationCorrectCalculator();

    @Test
    void 正确等于可解析且一致() {
        ListEvalDataSet data = new ListEvalDataSet(null, null,
                List.of(
                        new CitationSample("d1", 1, 1, true, true, T0),
                        new CitationSample("d2", 1, 2, true, true, T0),
                        new CitationSample("d3", 0, null, false, true, T0), // 不可解析
                        new CitationSample("d4", 1, 9, true, false, T0)),  // 页码不一致
                null, null, null);

        MetricValue v = calc.compute(data, EvalWindow.since(T0.minusSeconds(1)));

        assertThat(v.available()).isTrue();
        assertThat((Double) v.value()).isEqualTo(0.5);
    }

    @Test
    void 时间窗过滤窗外引用() {
        ListEvalDataSet data = new ListEvalDataSet(null, null,
                List.of(
                        new CitationSample("d1", 1, 1, true, true, T0),
                        new CitationSample("d2", 1, 1, false, false, T0.minusSeconds(3))),
                null, null, null);

        MetricValue v = calc.compute(data, new EvalWindow(T0, T0));

        assertThat((Double) v.value()).isEqualTo(1.0);
    }

    @Test
    void 无引用行返回missing() {
        MetricValue v = calc.compute(ListEvalDataSet.empty(), EvalWindow.all());

        assertThat(v.available()).isFalse();
        assertThat(v.note()).contains("引用");
    }
}
