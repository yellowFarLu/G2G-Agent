package com.wikiagent.domain.rule;

import java.time.Instant;

/**
 * 一次规则计算（规格 §2.3）：输入快照/中间量/输出全留存；
 * 确定性引擎保证同一 inputSnapshot+ruleVersion 必得同一 output（AC-D1 判定依据）。
 */
public record RuleComputation(
        Long id,
        String ruleCode,
        int ruleVersion,
        String docId,
        String inputSnapshot,
        String intermediatesJson,
        String outputJson,
        ComputationStatus status,
        String error,
        long durationMs,
        String traceId,
        Instant computedAt) {

    public enum ComputationStatus {
        SUCCESS,
        FAILED
    }
}
