package com.wikiagent.domain.eval.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 分位数工具（自写实现，不引入额外依赖）。
 * <p>
 * 采用最近秩法（nearest-rank，CEILING）：rank = ceil(p/100 × n)，取升序第 rank 个值（1 基）。
 * 选择该方法而非线性插值，是为了让分位值恒等于真实观测值（监控口径可解释、可复现）。
 */
final class Percentiles {

    private Percentiles() {
    }

    /**
     * @param values 观测值（不被修改）
     * @param p      百分位 (0,100]
     * @return 分位值；空列表返回 null
     */
    static Long percentile(List<Long> values, double p) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        long rank = (long) Math.ceil((p / 100.0) * n);
        if (rank < 1) {
            rank = 1;
        }
        if (rank > n) {
            rank = n;
        }
        return sorted.get((int) rank - 1);
    }
}
