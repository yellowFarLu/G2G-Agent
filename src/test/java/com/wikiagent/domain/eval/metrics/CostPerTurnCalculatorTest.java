package com.wikiagent.domain.eval.metrics;

import com.wikiagent.domain.eval.EvalWindow;
import com.wikiagent.domain.eval.MetricValue;
import com.wikiagent.domain.eval.data.ListEvalDataSet;
import com.wikiagent.domain.eval.data.ModelCallSample;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CostPerTurnCalculatorTest {

    private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");
    private final CostPerTurnCalculator calc = new CostPerTurnCalculator();

    @Test
    void 全用途成本总和除以CHAT行数() {
        ListEvalDataSet data = new ListEvalDataSet(null, null, null, null, null,
                List.of(
                        new ModelCallSample("CHAT", "p", "m", 100L, 0.010, "OK", T0),
                        new ModelCallSample("CHAT", "p", "m", 200L, 0.020, "OK", T0),
                        new ModelCallSample("CHAT", "p", "m", 300L, 0.030, "OK", T0),
                        new ModelCallSample("CHAT", "p", "m", 400L, 0.040, "OK", T0),
                        new ModelCallSample("RERANK", "p", "m", 50L, 0.005, "OK", T0)));

        MetricValue v = calc.compute(data, EvalWindow.since(T0.minusSeconds(1)));

        // (0.010+0.020+0.030+0.040+0.005) / 4 个 CHAT 轮
        assertThat(v.available()).isTrue();
        assertThat((Double) v.value()).isCloseTo(0.105 / 4, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void 时间窗排除窗外成本与轮次() {
        ListEvalDataSet data = new ListEvalDataSet(null, null, null, null, null,
                List.of(
                        new ModelCallSample("CHAT", "p", "m", 100L, 0.010, "OK", T0),
                        new ModelCallSample("CHAT", "p", "m", 200L, 0.900, "OK", T0.minusSeconds(4))));

        MetricValue v = calc.compute(data, new EvalWindow(T0, T0));

        assertThat((Double) v.value()).isEqualTo(0.010);
    }

    @Test
    void 无CHAT行时返回missing而不是零成本() {
        ListEvalDataSet data = new ListEvalDataSet(null, null, null, null, null,
                List.of(new ModelCallSample("RERANK", "p", "m", 50L, 0.005, "OK", T0)));

        MetricValue v = calc.compute(data, EvalWindow.all());

        assertThat(v.available()).isFalse();
        assertThat(v.note()).contains("CHAT");
    }

    @Test
    void 有CHAT行但无成本估算时如实报零() {
        ListEvalDataSet data = new ListEvalDataSet(null, null, null, null, null,
                List.of(new ModelCallSample("CHAT", "p", "m", 100L, null, "OK", T0)));

        MetricValue v = calc.compute(data, EvalWindow.all());

        assertThat(v.available()).isTrue();
        assertThat((Double) v.value()).isEqualTo(0.0);
    }
}
