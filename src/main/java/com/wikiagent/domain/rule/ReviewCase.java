package com.wikiagent.domain.rule;

import java.time.Instant;

/**
 * 复核案件（规格 §D4/§2.4）：聚合字段级差异、来源、置信度、初审结论；
 * 处置（通过/驳回/编辑）写 resolutionJson + field_version + EDITED/REVIEWED edge + task_event。
 */
public record ReviewCase(
        Long id,
        ReviewCaseType caseType,
        String docId,
        Integer versionNo,
        String fieldKey,
        String diffJson,
        String source,
        Double confidence,
        ReviewCaseStatus status,
        Long humanTaskId,
        String taskId,
        String ruleCode,
        Integer ruleVersion,
        Long computationId,
        String resolutionJson,
        String resolvedBy,
        Instant createdAt,
        Instant resolvedAt) {

    public enum ReviewCaseType {
        /** 抽取低置信/校验失败（B4.3）。 */
        LOW_CONFIDENCE,
        /** 材料 A-B 比对存在差异（D2 materialDiff 算子）。 */
        MATERIAL_DIFF,
        /** 规则计算结果与既有字段不一致。 */
        RULE_MISMATCH
    }

    public enum ReviewCaseStatus {
        OPEN,
        APPROVED,
        REJECTED,
        EDITED
    }

    /** 处置动作（规格 §D4）。 */
    public enum ReviewAction {
        APPROVE,
        REJECT,
        EDIT
    }
}
