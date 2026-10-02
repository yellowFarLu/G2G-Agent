package com.wikiagent.domain.eval.metrics;

import com.wikiagent.domain.eval.EvalWindow;
import com.wikiagent.domain.eval.MetricValue;
import com.wikiagent.domain.eval.data.EvalDataSource;
import com.wikiagent.domain.eval.data.FieldSample;

/**
 * 1) fieldAccuracyRate 字段准确率。
 * <p>
 * 数据源：extracted_field（评测种子造数 / 模型抽取落库行），窗口内 valid=true 占比。
 * 无字段行 → missing（不把“无数据”报成 1.0）。
 */
public final class FieldAccuracyCalculator implements MetricCalculator {

    public static final String KEY = "fieldAccuracyRate";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public MetricValue compute(EvalDataSource data, EvalWindow window) {
        long total = 0;
        long valid = 0;
        for (FieldSample f : data.fields()) {
            if (!window.contains(f.at())) {
                continue;
            }
            total++;
            if (f.valid()) {
                valid++;
            }
        }
        if (total == 0) {
            return MetricValue.missing(KEY, "窗口内无 extracted_field 行");
        }
        return MetricValue.of(KEY, (double) valid / total);
    }
}
