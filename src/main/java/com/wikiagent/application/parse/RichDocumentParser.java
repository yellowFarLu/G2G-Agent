package com.wikiagent.application.parse;

import com.wikiagent.domain.parse.EncryptedDocumentException;
import com.wikiagent.domain.parse.model.DocKind;
import com.wikiagent.domain.parse.model.ParsedDocument;
import com.wikiagent.domain.parse.model.ParsedPage;
import com.wikiagent.domain.parse.spi.AudioRequest;
import com.wikiagent.domain.parse.spi.Capability;
import com.wikiagent.domain.parse.spi.OcrRequest;
import com.wikiagent.domain.parse.spi.OcrResult;
import com.wikiagent.domain.parse.spi.TranscriptResult;
import com.wikiagent.infrastructure.parse.TikaTextExtractor;
import com.wikiagent.service.ingest.DocumentParser;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 子项目 B 富解析器（应用层）：按介质类型分流——
 * 文本/OOXML 走原解析链路；旧版 .doc/.xls 走 Tika；PDF 逐页文本层抽取，
 * 文本稀少的扫描页栅格化后走 OCR；图片走 OCR；录音走 ASR。
 * <p>
 * 加密 PDF：无可用内容时抛 {@link EncryptedDocumentException}（handler 建 DECRYPT 人工任务）。
 * 损坏文件：抛 IllegalStateException（handler 归类 PARSE_FAILED，致命不重试）。
 * AI 不可用：图片/录音/全扫描件返回 aiSkipped=true（流水线终态 AI_SKIPPED，不静默产出空 chunk）。
 */
@Component
public class RichDocumentParser {

    private static final Logger log = LoggerFactory.getLogger(RichDocumentParser.class);

    /** 扫描页栅格化缩放（72dpi * 2 = 144dpi，兼顾 OCR 精度与体积）。 */
    private static final float RENDER_SCALE = 2.0f;

    private final ParseInputValidator validator;
    private final DocumentAiGateway gateway;
    private final DocumentParser legacyParser;
    private final TikaTextExtractor tika;
    private final PageConflictDetector conflictDetector;

    public RichDocumentParser(ParseInputValidator validator,
                              DocumentAiGateway gateway,
                              DocumentParser legacyParser,
                              TikaTextExtractor tika,
                              PageConflictDetector conflictDetector) {
        this.validator = validator;
        this.gateway = gateway;
        this.legacyParser = legacyParser;
        this.tika = tika;
        this.conflictDetector = conflictDetector;
    }

    public ParsedDocument parse(String docId, String filename, byte[] bytes, String password) {
        validator.validate(filename, bytes);
        DocKind kind = validator.kindOf(filename);
        return switch (kind) {
            case TEXT -> plain(DocKind.TEXT, new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            case OFFICE_OOXML -> plain(DocKind.OFFICE_OOXML, legacyParser.parse(filename, bytes));
            case OFFICE_LEGACY -> plain(DocKind.OFFICE_LEGACY, tika.extract(bytes, filename));
            case PDF -> parsePdf(docId, bytes, password);
            case IMAGE -> parseImage(docId, filename, bytes);
            case AUDIO -> parseAudio(docId, filename, bytes);
        };
    }

    private ParsedDocument plain(DocKind kind, String text) {
        String t = text == null ? "" : text.strip();
        return ParsedDocument.of(kind, t, List.of(ParsedPage.text(1, t)));
    }

    private ParsedDocument parsePdf(String docId, byte[] bytes, String password) {
        try (PDDocument doc = openPdf(bytes, password)) {
            int pages = doc.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            List<ParsedPage> parsed = new ArrayList<>(pages);
            int scanned = 0;
            int ocred = 0;
            for (int i = 0; i < pages; i++) {
                int pageNo = i + 1;
                String text = extractPageText(stripper, doc, pageNo, password);
                String stripped = text.strip();
                if (stripped.length() < scannedThreshold()) {
                    scanned++;
                    ParsedPage page = ocrPdfPage(docId, doc, i, pageNo, text);
                    if (page.ocr() != null) {
                        ocred++;
                    }
                    parsed.add(page);
                } else if (PageConflictDetector.looksGarbled(stripped) && gateway.has(Capability.OCR)) {
                    // 有文本层但疑似 cmap 乱码：双跑 OCR 做差异比对（冲突不静默选一个）
                    byte[] png = renderPagePng(doc, i);
                    var ocr = gateway.ocrOptional(
                            new OcrRequest(docId, pageNo, png, "image/png")).orElse(null);
                    parsed.add(conflictDetector.reconcile(pageNo, stripped, ocr));
                } else {
                    parsed.add(ParsedPage.text(pageNo, stripped));
                }
            }
            // 全部页判定为扫描且无一页 OCR 成功（供应商不可用/熔断）→ AI 跳过，不静默空入库
            boolean aiSkipped = scanned > 0 && ocred == 0 && scanned == pages;
            if (scanned > 0 && ocred < scanned) {
                log.warn("PDF 存在未完成 OCR 的扫描页 docId={} scanned={} ocred={}", docId, scanned, ocred);
            }
            String fullText = String.join("\n\n",
                    parsed.stream().map(ParsedPage::text).filter(t -> !t.isBlank()).toList());
            return new ParsedDocument(DocKind.PDF, fullText, parsed, List.of(), null, aiSkipped);
        } catch (EncryptedDocumentException e) {
            throw e;
        } catch (RuntimeException e) {
            // 供应商熔断/超时等保持原异常类型上抛，handler 据此判定可重试
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("PDF 解析失败: " + e.getMessage(), e);
        }
    }

    private PDDocument openPdf(byte[] bytes, String password) {
        try {
            return password == null || password.isBlank()
                    ? Loader.loadPDF(bytes)
                    : Loader.loadPDF(bytes, password);
        } catch (InvalidPasswordException e) {
            boolean tried = password != null && !password.isBlank();
            throw new EncryptedDocumentException(
                    tried ? "PDF 口令错误，文档无法打开" : "PDF 已加密，请提供打开口令", tried, e);
        } catch (Exception e) {
            throw new IllegalStateException("PDF 解析失败: " + e.getMessage(), e);
        }
    }

    /** 单页文本抽取；加密且无权限时转 DECRYPT 人工流程。 */
    private String extractPageText(PDFTextStripper stripper, PDDocument doc, int pageNo, String password) {
        try {
            stripper.setStartPage(pageNo);
            stripper.setEndPage(pageNo);
            return stripper.getText(doc);
        } catch (InvalidPasswordException e) {
            throw new EncryptedDocumentException(
                    "PDF 已加密" + (password == null || password.isBlank() ? "，请提供打开口令" : "，口令不正确"),
                    password != null && !password.isBlank(), e);
        } catch (Exception e) {
            throw new IllegalStateException("PDF 第 " + pageNo + " 页文本抽取失败: " + e.getMessage(), e);
        }
    }

    /**
     * 未配置 OCR（none/无 Key）→ 降级扫描页；已配置但调用失败（超时/熔断）异常上抛，
     * 由 handler 归类为可重试任务失败（规格：已配置供应商时扫描件失败可重试，不静默跳过）。
     */
    private ParsedPage ocrPdfPage(String docId, PDDocument doc, int pageIndex, int pageNo, String fallback) {
        if (!gateway.has(Capability.OCR)) {
            return ParsedPage.skippedScan(pageNo, fallback == null ? "" : fallback.strip());
        }
        try {
            byte[] png = renderPagePng(doc, pageIndex);
            Optional<OcrResult> result =
                    gateway.ocrOptional(new OcrRequest(docId, pageNo, png, "image/png"));
            return result.map(r -> ParsedPage.ocred(pageNo, r))
                    .orElseGet(() -> ParsedPage.skippedScan(pageNo, fallback == null ? "" : fallback.strip()));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("扫描页 OCR 失败 page=" + pageNo + ": " + e.getMessage(), e);
        }
    }

    private byte[] renderPagePng(PDDocument doc, int pageIndex) throws Exception {
        PDFRenderer renderer = new PDFRenderer(doc);
        var image = renderer.renderImage(pageIndex, RENDER_SCALE);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IllegalStateException("PDF 页面栅格化为 PNG 失败 page=" + (pageIndex + 1));
        }
        return out.toByteArray();
    }

    private ParsedDocument parseImage(String docId, String filename, byte[] bytes) {
        String ext = ParseInputValidator.extOf(filename);
        if (!gateway.has(Capability.OCR)) {
            log.warn("图片 OCR 能力不可用，文档标记 AI_SKIPPED docId={} file={}", docId, filename);
            return ParsedDocument.skipped(DocKind.IMAGE);
        }
        Optional<OcrResult> ocr = gateway.ocrOptional(
                new OcrRequest(docId, 1, bytes, ParseInputValidator.imageMime(ext)));
        if (ocr.isEmpty()) {
            return ParsedDocument.skipped(DocKind.IMAGE);
        }
        OcrResult r = ocr.get();
        return new ParsedDocument(DocKind.IMAGE, r.fullText(),
                List.of(ParsedPage.ocred(1, r)), List.of(), null, false);
    }

    private ParsedDocument parseAudio(String docId, String filename, byte[] bytes) {
        if (!gateway.has(Capability.ASR)) {
            log.warn("录音 ASR 能力不可用，文档标记 AI_SKIPPED docId={} file={}", docId, filename);
            return ParsedDocument.skipped(DocKind.AUDIO);
        }
        Optional<TranscriptResult> transcript = gateway.transcribeOptional(
                new AudioRequest(docId, bytes, guessAudioMime(filename), filename, null));
        if (transcript.isEmpty()) {
            return ParsedDocument.skipped(DocKind.AUDIO);
        }
        TranscriptResult t = transcript.get();
        return new ParsedDocument(DocKind.AUDIO, t.fullText(), List.of(), List.of(), t, false);
    }

    private int scannedThreshold() {
        return validator.scannedThreshold();
    }

    static String guessAudioMime(String filename) {
        String ext = ParseInputValidator.extOf(filename);
        return switch (ext) {
            case "wav" -> "audio/wav";
            case "m4a", "aac" -> "audio/aac";
            case "flac" -> "audio/flac";
            case "ogg" -> "audio/ogg";
            default -> "audio/mpeg";
        };
    }
}
