package com.wikiagent.domain.lineage;

/**
 * 血缘边类型（C1/C2）：DERIVED 清洗/切分派生；EXTRACTED 字段抽取；STITCHED 跨页表格；
 * RULED 规则引擎（D 预留）；EDITED 人工编辑；CITED 引用；SUPERSEDES 版本更替。
 */
public enum EdgeType {
    DERIVED,
    EXTRACTED,
    STITCHED,
    RULED,
    EDITED,
    CITED,
    SUPERSEDES
}
