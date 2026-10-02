package com.wikiagent.domain.eval.metrics;

import com.wikiagent.domain.eval.EvalWindow;
import com.wikiagent.domain.eval.MetricValue;
import com.wikiagent.domain.eval.data.ListEvalDataSet;
import com.wikiagent.domain.eval.data.ReviewDisposition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HumanEditCalculatorTest {

    private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");
    private final HumanEditCalculator calc = new HumanEditCalculator();

    @Test
    void EDITED除以已处置案件_OPEN不计入分母() {
        ListEvalDataSet data = new ListEvalDataSet(null, null, null, null,
                List.of(
                        new ReviewDisposition("c1", "APPROVED", T0),
                        new ReviewDisposition("c2", "REJECTED", T0),
                        new ReviewDisposition("c3", "EDITED", T0),
                        new ReviewDisposition("c4", "EDITED", T0),
                        new ReviewDisposition("c5", "OPEN", T0)),
                null);

        MetricValue v = calc.compute(data, EvalWindow.since(T0.minusSeconds(1)));

        assertThat(v.available()).isTrue();
        assertThat((Double) v.value()).isEqualTo(0.5); // 2 / (1+1+2)
    }

    @Test
    void 时间窗过滤窗外案件() {
        ListEvalDataSet data = new ListEvalDataSet(null, null, null, null,
                List.of(
                        new ReviewDisposition("c1", "EDITED", T0),
                        new ReviewDisposition("c2", "APPROVED", T0.minusSeconds(9))),
                null);

        MetricValue v = calc.compute(data, new EvalWindow(T0, T0));

        assertThat((Double) v.value()).isEqualTo(1.0);
    }

    @Test
    void 仅有OPEN时返回missing() {
        ListEvalDataSet data = new ListEvalDataSet(null, null, null, null,
                List.of(new ReviewDisposition("c1", "OPEN", T0)), null);

        MetricValue v = calc.compute(data, EvalWindow.all());

        assertThat(v.available()).isFalse();
        assertThat(v.note()).contains("review_case");
    }
}
