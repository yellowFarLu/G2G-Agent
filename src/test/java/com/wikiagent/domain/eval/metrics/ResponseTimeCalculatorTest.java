package com.wikiagent.domain.eval.metrics;

import com.wikiagent.domain.eval.EvalWindow;
import com.wikiagent.domain.eval.MetricValue;
import com.wikiagent.domain.eval.data.ListEvalDataSet;
import com.wikiagent.domain.eval.data.ModelCallSample;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseTimeCalculatorTest {

    private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");
    private final ResponseTimeCalculator calc = new ResponseTimeCalculator();

    @Test
    void 最近秩法分位精确值() {
        ListEvalDataSet data = new ListEvalDataSet(null, null, null, null, null,
                List.of(
                        call(100L, "OK", T0),
                        call(200L, "OK", T0),
                        call(300L, "OK", T0),
                        call(400L, "OK", T0)));

        MetricValue v = calc.compute(data, EvalWindow.since(T0.minusSeconds(1)));

        assertThat(v.available()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) v.value();
        // rank=ceil(p/100*n)：p50→rank2=200；p95→rank4=400
        assertThat(p).containsEntry("p50", 200L)
                .containsEntry("p95", 400L)
                .containsEntry("sampleCount", 4);
    }

    @Test
    void 非OK与空延迟与窗外行均排除() {
        ListEvalDataSet data = new ListEvalDataSet(null, null, null, null, null,
                List.of(
                        call(200L, "OK", T0),
                        call(999L, "ERROR", T0),       // 失败行排除
                        new ModelCallSample("CHAT", "p", "m", null, null, "OK", T0), // 空延迟排除
                        call(50L, "OK", T0.minusSeconds(2)))); // 窗外排除

        MetricValue v = calc.compute(data, new EvalWindow(T0, T0));

        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) v.value();
        assertThat(p).containsEntry("p50", 200L)
                .containsEntry("p95", 200L)
                .containsEntry("sampleCount", 1);
    }

    @Test
    void 无样本返回missing() {
        MetricValue v = calc.compute(ListEvalDataSet.empty(), EvalWindow.all());

        assertThat(v.available()).isFalse();
        assertThat(v.note()).contains("model_call_log");
    }

    private static ModelCallSample call(Long latency, String status, Instant at) {
        return new ModelCallSample("CHAT", "p", "m", latency, 0.01, status, at);
    }
}
