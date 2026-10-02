package com.wikiagent.domain.eval;

import java.time.Instant;

/**
 * 指标计算时间窗（规格 §H AC-H3：给定时间窗/评测运行 ID）。
 * <p>
 * 闭区间 [from, to]；任一端为 null 表示该侧不封闭（全量）。
 * 纯 Java 值对象，domain/eval 不依赖 Spring/JPA。
 */
public record EvalWindow(Instant from, Instant to) {

    /** 全量时间窗（评测运行 ID 维度由调用方自行造数隔离）。 */
    public static EvalWindow all() {
        return new EvalWindow(null, null);
    }

    /** 仅下界（典型用法：本次评测运行开始时刻起）。 */
    public static EvalWindow since(Instant from) {
        return new EvalWindow(from, null);
    }

    /**
     * 时间点是否落在窗内：
     * 有界窗口下 null 时间戳不落入（无法定位）；无界窗口下 null 视为落入（全量造数）。
     */
    public boolean contains(Instant at) {
        if (at == null) {
            return from == null && to == null;
        }
        if (from != null && at.isBefore(from)) {
            return false;
        }
        return to == null || !at.isAfter(to);
    }
}
