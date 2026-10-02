package com.wikiagent.domain.eval;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单项指标值。
 * <ul>
 *   <li>标量指标（率/成本）：{@code value} 为 Double；</li>
 *   <li>复合指标：{@code value} 为有序 Map（judgeErrorRates 含 factErrorRate/structureErrorRate；
 *       responseTimeP50P95 含 p50/p95）；</li>
 *   <li>缺数据源（无样本行）时 {@code value=null} 且 {@code note} 说明原因——
 *       遵守“禁止伪造事实”，不编造 0.0/1.0。</li>
 * </ul>
 */
public record MetricValue(String key, Object value, String note) {

    public static MetricValue of(String key, double numeric) {
        return new MetricValue(key, numeric, null);
    }

    public static MetricValue composite(String key, Map<String, Object> values) {
        return new MetricValue(key, new LinkedHashMap<>(values), null);
    }

    /** 缺数据源：值为 null 并附原因说明（报告中如实呈现）。 */
    public static MetricValue missing(String key, String reason) {
        return new MetricValue(key, null, reason);
    }

    public boolean available() {
        return value != null;
    }
}
