package com.wikiagent.domain.parse.model;

/**
 * 入库文档的介质类型（决定 INGEST 条件步骤分流）。
 */
public enum DocKind {
    /** 纯文本（txt/md）。 */
    TEXT,
    /** 可移植文档（pdf，可能含文本层或扫描页）。 */
    PDF,
    /** OOXML 办公文档（docx/xlsx）。 */
    OFFICE_OOXML,
    /** 旧版二进制办公文档（doc/xls，Tika 兜底）。 */
    OFFICE_LEGACY,
    /** 图片（png/jpg/tiff…，依赖 OCR）。 */
    IMAGE,
    /** 录音（mp3/wav/m4a…，依赖 ASR）。 */
    AUDIO
}
