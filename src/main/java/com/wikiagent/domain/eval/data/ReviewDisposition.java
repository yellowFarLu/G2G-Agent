package com.wikiagent.domain.eval.data;

import java.time.Instant;

/**
 * 复核案件处置投影（review_case → 人工修改率）。
 *
 * @param caseId 案件标识
 * @param status 处置状态（OPEN / APPROVED / REJECTED / EDITED；未识别串保守忽略）
 * @param at     处置时间（resolvedAt，缺失时回退 createdAt；窗口过滤依据）
 */
public record ReviewDisposition(String caseId, String status, Instant at) {
}
