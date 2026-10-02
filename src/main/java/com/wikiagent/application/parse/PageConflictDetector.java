package com.wikiagent.application.parse;

import com.wikiagent.config.ParseProperties;
import com.wikiagent.domain.parse.model.ParsedPage;
import com.wikiagent.domain.parse.spi.OcrResult;
import org.springframework.stereotype.Component;

/**
 * 文本层 ↔ OCR 双跑结果冲突检测（规格 §2.1）。
 * 相似度低于 {@code wikiagent.parse.text-conflict-similarity}（默认 0.85）→ CONFLICT，
 * 两份文本都保留；选文本策略：冲突且 OCR 高置信(≥0.8) 采用 OCR，否则保留文本层待人工。
 */
@Component
public class PageConflictDetector {

    /** 冲突时采信 OCR 的置信门槛。 */
    static final double TRUST_OCR_CONFIDENCE = 0.8;

    private final ParseProperties props;

    public PageConflictDetector(ParseProperties props) {
        this.props = props;
    }

    public ParsedPage reconcile(int pageNo, String textLayer, OcrResult ocr) {
        if (ocr == null) {
            return ParsedPage.text(pageNo, textLayer == null ? "" : textLayer);
        }
        double similarity = TextSimilarity.similarity(textLayer, ocr.fullText());
        double diffRate = 1.0 - similarity;
        boolean conflict = similarity < props.getTextConflictSimilarity();
        String chosen;
        if (!conflict) {
            chosen = textLayer == null ? ocr.fullText() : textLayer;
        } else {
            chosen = ocr.confidence() >= TRUST_OCR_CONFIDENCE ? ocr.fullText()
                    : (textLayer == null ? ocr.fullText() : textLayer);
        }
        return ParsedPage.reconciled(pageNo, textLayer, ocr, chosen, conflict, diffRate);
    }

    /**
     * 文本层疑似乱码（触发与 OCR 双跑的窄门）：U+FFFD 占比高或不可打印控制符占比高。
     * 正常扫描页（文本极少）由扫描阈值走纯 OCR，不进本路径。
     */
    public static boolean looksGarbled(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        int bad = 0;
        int meaningful = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            meaningful++;
            if (c == '�' || (Character.isISOControl(c))) {
                bad++;
            }
        }
        return meaningful >= 10 && (double) bad / meaningful >= 0.1;
    }
}
