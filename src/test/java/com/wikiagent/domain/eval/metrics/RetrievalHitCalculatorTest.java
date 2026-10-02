package com.wikiagent.domain.eval.metrics;

import com.wikiagent.domain.eval.EvalWindow;
import com.wikiagent.domain.eval.MetricValue;
import com.wikiagent.domain.eval.data.ListEvalDataSet;
import com.wikiagent.domain.eval.data.RetrievalSample;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalHitCalculatorTest {

    private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");
    private final RetrievalHitCalculator calc = new RetrievalHitCalculator();

    @Test
    void 仅RETRIEVED且有相关性标签的行计入分母() {
        ListEvalDataSet data = new ListEvalDataSet(null,
                List.of(
                        new RetrievalSample("d1", true, "RETRIEVED", T0),
                        new RetrievalSample("d2", true, "RETRIEVED", T0),
                        new RetrievalSample("d3", false, "RETRIEVED", T0),
                        new RetrievalSample("d4", null, "RETRIEVED", T0),   // 无反馈：剔除
                        new RetrievalSample("d1", true, "CITED", T0)),     // CITED 不计入
                null, null, null, null);

        MetricValue v = calc.compute(data, EvalWindow.since(T0.minusSeconds(1)));

        assertThat(v.available()).isTrue();
        assertThat((Double) v.value()).isEqualTo(2.0 / 3.0);
    }

    @Test
    void 时间窗过滤窗外检索事件() {
        ListEvalDataSet data = new ListEvalDataSet(null,
                List.of(
                        new RetrievalSample("d1", true, "RETRIEVED", T0),
                        new RetrievalSample("d2", false, "RETRIEVED", T0.minusSeconds(5))),
                null, null, null, null);

        MetricValue v = calc.compute(data, new EvalWindow(T0, T0));

        assertThat((Double) v.value()).isEqualTo(1.0);
    }

    @Test
    void 无标签行时返回missing() {
        ListEvalDataSet data = new ListEvalDataSet(null,
                List.of(new RetrievalSample("d1", null, "RETRIEVED", T0)),
                null, null, null, null);

        MetricValue v = calc.compute(data, EvalWindow.all());

        assertThat(v.available()).isFalse();
        assertThat(v.note()).contains("RETRIEVED");
    }
}
