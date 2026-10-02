package com.wikiagent.domain.eval.metrics;

import com.wikiagent.domain.eval.EvalWindow;
import com.wikiagent.domain.eval.MetricValue;
import com.wikiagent.domain.eval.data.EvalDataSource;
import com.wikiagent.domain.eval.data.JudgeVerdict;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 4) judgeErrorRates 评判错误率（七项指标中的第 4 项，复合两个子率）：
 * <ul>
 *   <li>{@code factErrorRate}：录制固件中被评判为事实错误的比例；</li>
 *   <li>{@code structureErrorRate}：被评判为结构错误的比例。</li>
 * </ul>
 * 数据源：resources/eval/stubs/judge/* 录制的“问题→评判结果”（离线，无真实 LLM）。
 * 无录制行 → missing。
 */
public final class JudgeErrorCalculator implements MetricCalculator {

    /** 七项指标中的指标键（复合项，value 内含 factErrorRate/structureErrorRate 两个子键）。 */
    public static final String KEY = "judgeErrorRates";

    public static final String SUB_FACT = "factErrorRate";
    public static final String SUB_STRUCTURE = "structureErrorRate";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public MetricValue compute(EvalDataSource data, EvalWindow window) {
        long total = 0;
        long factErrors = 0;
        long structureErrors = 0;
        for (JudgeVerdict v : data.judgeVerdicts()) {
            if (!window.contains(v.at())) {
                continue;
            }
            total++;
            if (v.factError()) {
                factErrors++;
            }
            if (v.structureError()) {
                structureErrors++;
            }
        }
        if (total == 0) {
            return MetricValue.missing(KEY, "窗口内无 judge 录制评判行（resources/eval/stubs/judge）");
        }
        Map<String, Object> rates = new LinkedHashMap<>();
        rates.put(SUB_FACT, (double) factErrors / total);
        rates.put(SUB_STRUCTURE, (double) structureErrors / total);
        return MetricValue.composite(KEY, rates);
    }
}
