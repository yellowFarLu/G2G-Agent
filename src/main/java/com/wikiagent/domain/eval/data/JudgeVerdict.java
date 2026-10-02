package com.wikiagent.domain.eval.data;

import java.time.Instant;

/**
 * judge 录制评判投影（resources/eval/stubs/judge/* 离线固件）。
 *
 * @param id             固件标识
 * @param factError      是否判定事实错误
 * @param structureError 是否判定结构错误
 * @param at             评判时间（加载时统一打戳，窗口过滤依据）
 */
public record JudgeVerdict(String id, boolean factError, boolean structureError, Instant at) {
}
