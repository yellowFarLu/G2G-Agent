package com.wikiagent.domain.parse.spi;

import java.util.List;

/**
 * OCR 结果：整页全文 + 有序片段（含坐标/置信度）+ 整页置信度(0-1)。
 */
public record OcrResult(String fullText, List<OcrSpan> spans, double confidence) {

    public OcrResult {
        spans = spans == null ? List.of() : List.copyOf(spans);
    }
}
