package com.wikiagent.domain.rule;

/**
 * 规则集版本生命周期（规格 §D1）：草稿 → 发布生效 → 归档（不可再执行，仅可回放查询）。
 */
public enum RuleStatus {
    DRAFT,
    ACTIVE,
    ARCHIVED
}
