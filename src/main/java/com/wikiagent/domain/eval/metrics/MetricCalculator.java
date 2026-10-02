package com.wikiagent.domain.eval.metrics;

import com.wikiagent.domain.eval.EvalWindow;
import com.wikiagent.domain.eval.MetricValue;
import com.wikiagent.domain.eval.data.EvalDataSource;

/**
 * 指标计算器统一接口（规格 §H AC-H3）。
 * <p>
 * 七个计算器：字段准确率 / 检索命中率 / 引用正确率 / 评判事实+结构错误率 /
 * 人工修改率 / 响应时间 P50P95 / 单次成本。全部只读、确定性、零 LLM。
 * 缺数据源时返回 {@link MetricValue#missing(String, String)}，不得编造数值。
 */
public interface MetricCalculator {

    /** 报告 summary 中的指标键（七项）。 */
    String key();

    /** 在给定时间窗内从只读数据集计算指标。 */
    MetricValue compute(EvalDataSource data, EvalWindow window);
}
