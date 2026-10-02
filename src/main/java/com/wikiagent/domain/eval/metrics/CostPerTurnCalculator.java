package com.wikiagent.domain.eval.metrics;

import com.wikiagent.domain.eval.EvalWindow;
import com.wikiagent.domain.eval.MetricValue;
import com.wikiagent.domain.eval.data.EvalDataSource;
import com.wikiagent.domain.eval.data.ModelCallSample;

/**
 * 7) costPerTurn 单次对话成本。
 * <p>
 * 数据源：model_call_log（V14）。
 * 分子：窗口内全部 purpose 的 cost_estimate 之和（非空行；失败调用也计费——供应商已扣费）；
 * 分母：窗口内 purpose=CHAT 的行数（“轮”按 CHAT 调用计）。
 * CHAT 行数为 0 → missing（不把分母为 0 报成 0 成本）。
 */
public final class CostPerTurnCalculator implements MetricCalculator {

    public static final String KEY = "costPerTurn";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public MetricValue compute(EvalDataSource data, EvalWindow window) {
        double totalCost = 0.0;
        long chatTurns = 0;
        boolean anyCost = false;
        for (ModelCallSample c : data.modelCalls()) {
            if (!window.contains(c.at())) {
                continue;
            }
            if (c.costEstimate() != null) {
                totalCost += c.costEstimate();
                anyCost = true;
            }
            if ("CHAT".equalsIgnoreCase(c.purpose())) {
                chatTurns++;
            }
        }
        if (chatTurns == 0) {
            return MetricValue.missing(KEY, "窗口内无 purpose=CHAT 的 model_call_log 行，无法按轮摊销成本");
        }
        // 无任何成本估算行时如实保留 0.0（成本配置缺失场景，与“无 CHAT 行”语义不同）
        return MetricValue.of(KEY, anyCost ? totalCost / chatTurns : 0.0);
    }
}
