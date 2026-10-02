package com.wikiagent.domain.eval.metrics;

import com.wikiagent.domain.eval.EvalWindow;
import com.wikiagent.domain.eval.MetricValue;
import com.wikiagent.domain.eval.data.EvalDataSource;
import com.wikiagent.domain.eval.data.ModelCallSample;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 6) responseTimeP50P95 响应时间分位（七项指标中的第 6 项，复合 p50/p95）。
 * <p>
 * 数据源：model_call_log.latency_ms（V14），窗口内 status=OK 且 latency_ms 非空的全部
 * purpose 行（INTENT/EXTRACT/CHAT/RERANK/JUDGE 构成端到端响应画像）。
 * 分位口径：最近秩法（见 {@link Percentiles}），值恒为真实观测值。无样本 → missing。
 */
public final class ResponseTimeCalculator implements MetricCalculator {

    public static final String KEY = "responseTimeP50P95";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public MetricValue compute(EvalDataSource data, EvalWindow window) {
        List<Long> latencies = new ArrayList<>();
        for (ModelCallSample c : data.modelCalls()) {
            if (!window.contains(c.at())) {
                continue;
            }
            if (c.latencyMs() == null || !"OK".equalsIgnoreCase(c.status())) {
                continue;
            }
            latencies.add(c.latencyMs());
        }
        if (latencies.isEmpty()) {
            return MetricValue.missing(KEY, "窗口内无 status=OK 且 latency_ms 非空的 model_call_log 行");
        }
        Map<String, Object> pcts = new LinkedHashMap<>();
        pcts.put("p50", Percentiles.percentile(latencies, 50));
        pcts.put("p95", Percentiles.percentile(latencies, 95));
        pcts.put("sampleCount", latencies.size());
        return MetricValue.composite(KEY, pcts);
    }
}
