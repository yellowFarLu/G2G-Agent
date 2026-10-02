package com.wikiagent.domain.eval.data;

import java.time.Instant;

/**
 * 模型调用行投影（model_call_log → 响应时间/单次成本）。
 *
 * @param purpose      调用用途（INTENT / EXTRACT / CHAT / RERANK / JUDGE）
 * @param provider     供应商
 * @param model        模型名
 * @param latencyMs    延迟毫秒（可空；响应时间只统计 status=OK 且非空行）
 * @param costEstimate 成本估算（可空；失败调用也计费——供应商已扣费）
 * @param status       调用状态（OK / ERROR）
 * @param at           调用时间（窗口过滤依据）
 */
public record ModelCallSample(String purpose, String provider, String model,
                              Long latencyMs, Double costEstimate, String status, Instant at) {
}
