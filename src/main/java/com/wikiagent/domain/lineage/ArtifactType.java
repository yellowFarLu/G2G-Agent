package com.wikiagent.domain.lineage;

/**
 * 文档产物类型（C1）：大文本不入库，仅存 contentRef 路径 + sha256。
 */
public enum ArtifactType {
    /** 源文件（血缘链头，原始字节）。 */
    RAW_FILE,
    PARSED_TEXT,
    CLEANED_TEXT,
    OCR_PAGE,
    LAYOUT,
    STITCHED_TABLE,
    EXTRACT_RESULT,
    /** 文本层/OCR 冲突页留痕（B3.3 CONFLICT artifact）。 */
    CONFLICT
}
