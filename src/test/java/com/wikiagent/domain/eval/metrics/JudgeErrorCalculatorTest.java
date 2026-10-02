package com.wikiagent.domain.eval.metrics;

import com.wikiagent.domain.eval.EvalWindow;
import com.wikiagent.domain.eval.MetricValue;
import com.wikiagent.domain.eval.data.JudgeVerdict;
import com.wikiagent.domain.eval.data.ListEvalDataSet;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JudgeErrorCalculatorTest {

    private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");
    private final JudgeErrorCalculator calc = new JudgeErrorCalculator();

    @Test
    void 事实率与结构率分别精确计算() {
        // 与 resources/eval/stubs/judge 4 条录制同分布：fact 2/4，structure 2/4
        ListEvalDataSet data = new ListEvalDataSet(null, null, null,
                List.of(
                        new JudgeVerdict("j1", true, false, T0),
                        new JudgeVerdict("j2", false, true, T0),
                        new JudgeVerdict("j3", true, true, T0),
                        new JudgeVerdict("j4", false, false, T0)),
                null, null);

        MetricValue v = calc.compute(data, EvalWindow.since(T0.minusSeconds(1)));

        assertThat(v.available()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> rates = (Map<String, Object>) v.value();
        assertThat(rates).containsEntry(JudgeErrorCalculator.SUB_FACT, 0.5)
                .containsEntry(JudgeErrorCalculator.SUB_STRUCTURE, 0.5);
    }

    @Test
    void 时间窗过滤窗外评判() {
        ListEvalDataSet data = new ListEvalDataSet(null, null, null,
                List.of(
                        new JudgeVerdict("j1", true, false, T0),
                        new JudgeVerdict("j2", true, true, T0.minusSeconds(7))),
                null, null);

        MetricValue v = calc.compute(data, new EvalWindow(T0, T0));

        @SuppressWarnings("unchecked")
        Map<String, Object> rates = (Map<String, Object>) v.value();
        assertThat(rates).containsEntry(JudgeErrorCalculator.SUB_FACT, 1.0)
                .containsEntry(JudgeErrorCalculator.SUB_STRUCTURE, 0.0);
    }

    @Test
    void 无录制评判返回missing() {
        MetricValue v = calc.compute(ListEvalDataSet.empty(), EvalWindow.all());

        assertThat(v.available()).isFalse();
        assertThat(v.note()).contains("judge");
    }
}
