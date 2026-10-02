package com.wikiagent.domain.parse.spi;

/**
 * OCR 文本片段：文本 + 可选包围盒 + 片段置信度(0-1)。
 */
public record OcrSpan(String text, BBox bbox, double confidence) {

    public OcrSpan(String text, double confidence) {
        this(text, null, confidence);
    }
}
