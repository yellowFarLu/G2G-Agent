package com.wikiagent.domain.eval.metrics;

import com.wikiagent.domain.eval.EvalWindow;
import com.wikiagent.domain.eval.MetricValue;
import com.wikiagent.domain.eval.data.FieldSample;
import com.wikiagent.domain.eval.data.ListEvalDataSet;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FieldAccuracyCalculatorTest {

    private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");
    private final FieldAccuracyCalculator calc = new FieldAccuracyCalculator();

    @Test
    void 窗内有效率精确计算() {
        ListEvalDataSet data = new ListEvalDataSet(
                List.of(new FieldSample("d1", "a", true, T0),
                        new FieldSample("d1", "b", true, T0),
                        new FieldSample("d1", "c", true, T0),
                        new FieldSample("d1", "d", true, T0),
                        new FieldSample("d1", "e", false, T0)),
                null, null, null, null, null);

        MetricValue v = calc.compute(data, EvalWindow.since(T0.minusSeconds(1)));

        assertThat(v.available()).isTrue();
        assertThat((Double) v.value()).isEqualTo(0.8);
    }

    @Test
    void 时间窗过滤窗外行() {
        ListEvalDataSet data = new ListEvalDataSet(
                List.of(new FieldSample("d1", "a", true, T0),
                        new FieldSample("d2", "x", false, T0.minusSeconds(10)),
                        new FieldSample("d2", "y", false, T0.plusSeconds(10))),
                null, null, null, null, null);

        MetricValue v = calc.compute(data, new EvalWindow(T0, T0));

        // 仅 1 条窗内且有效 → 1.0；窗外两条无效行被排除
        assertThat(v.available()).isTrue();
        assertThat((Double) v.value()).isEqualTo(1.0);
    }

    @Test
    void 无字段行返回missing不伪造数值() {
        MetricValue v = calc.compute(ListEvalDataSet.empty(), EvalWindow.all());

        assertThat(v.available()).isFalse();
        assertThat(v.value()).isNull();
        assertThat(v.note()).contains("extracted_field");
    }
}
