package com.wikiagent.domain.parse.model;

import com.wikiagent.domain.parse.spi.LayoutResult;
import com.wikiagent.domain.parse.spi.OcrResult;

/**
 * 单页解析结果。
 * <ul>
 *   <li>{@code scanned}：文本层稀少判定为扫描页；</li>
 *   <li>{@code textLayerText}：PDF 嵌入文本层（与 OCR 双跑时保留以备冲突比对）；</li>
 *   <li>{@code ocr}：OCR 结果（含坐标/置信度）；</li>
 *   <li>{@code layout}：版面分析块（B3 LAYOUT 步骤回填）；</li>
 *   <li>{@code conflict}/{@code diffRate}：文本层与 OCR 差异率超阈值（不静默选一个）。</li>
 * </ul>
 */
public record ParsedPage(
        int pageNo,
        String text,
        String textLayerText,
        boolean scanned,
        OcrResult ocr,
        LayoutResult layout,
        boolean conflict,
        double diffRate) {

    public static ParsedPage text(int pageNo, String text) {
        return new ParsedPage(pageNo, text, null, false, null, null, false, 0);
    }

    public static ParsedPage ocred(int pageNo, OcrResult ocr) {
        return new ParsedPage(pageNo, ocr.fullText(), null, true, ocr, null, false, 0);
    }

    public static ParsedPage skippedScan(int pageNo, String fallbackText) {
        return new ParsedPage(pageNo, fallbackText, fallbackText, true, null, null, false, 0);
    }

    /** 文本层/OCR 双跑比对结果（可能带冲突标记）。 */
    public static ParsedPage reconciled(int pageNo, String textLayerText, OcrResult ocr,
                                        String chosenText, boolean conflict, double diffRate) {
        return new ParsedPage(pageNo, chosenText, textLayerText, false, ocr, null, conflict, diffRate);
    }

    public ParsedPage withLayout(LayoutResult layoutResult) {
        return new ParsedPage(pageNo, text, textLayerText, scanned, ocr, layoutResult, conflict, diffRate);
    }
}
