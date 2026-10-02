package com.wikiagent.application.parse;

import com.wikiagent.config.ParseProperties;
import com.wikiagent.domain.parse.EncryptedDocumentException;
import com.wikiagent.domain.parse.model.DocKind;
import com.wikiagent.domain.parse.model.ParsedDocument;
import com.wikiagent.domain.parse.spi.AudioRequest;
import com.wikiagent.domain.parse.spi.BBox;
import com.wikiagent.domain.parse.spi.Capability;
import com.wikiagent.domain.parse.spi.DocumentAiProvider;
import com.wikiagent.domain.parse.spi.LayoutRequest;
import com.wikiagent.domain.parse.spi.LayoutResult;
import com.wikiagent.domain.parse.spi.OcrRequest;
import com.wikiagent.domain.parse.spi.OcrResult;
import com.wikiagent.domain.parse.spi.OcrSpan;
import com.wikiagent.domain.parse.spi.TableRequest;
import com.wikiagent.domain.parse.spi.TableResult;
import com.wikiagent.domain.parse.spi.TranscriptResult;
import com.wikiagent.domain.parse.spi.TranscriptSegment;
import com.wikiagent.infrastructure.parse.TikaTextExtractor;
import com.wikiagent.service.ingest.DocumentParser;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * B-2 富解析器分流测试：文本 PDF/扫描页 OCR/加密口令续跑/图片/录音/损坏件/旧版 .doc。
 * AI 供应商用内存桩，PDF/图片样本由 PDFBox/ImageIO 确定性生成，.doc 为提交的固定 OLE2 样本。
 */
class RichDocumentParserTest {

    private static final String OCR_TEXT = "STUB-OCR-INVOICE-No-12345";
    private static final String PDF_TEXT = "INVOICE NO 12345678 TOTAL AMOUNT 99.00 DOLLARS";

    /** 可配置能力/可用性并记录调用的桩供应商。 */
    static final class StubProvider implements DocumentAiProvider {
        Set<Capability> caps = Set.of(Capability.values());
        boolean available = true;
        int ocrCalls;
        int asrCalls;

        @Override
        public String name() {
            return "stub";
        }

        @Override
        public Set<Capability> capabilities() {
            return available ? caps : Set.of();
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public OcrResult ocr(OcrRequest request) {
            ocrCalls++;
            return new OcrResult(OCR_TEXT,
                    List.of(new OcrSpan(OCR_TEXT, BBox.of(1, 2, 30, 10), 0.91)), 0.91);
        }

        @Override
        public TranscriptResult transcribe(AudioRequest request) {
            asrCalls++;
            return new TranscriptResult("你好 世界",
                    List.of(new TranscriptSegment(0, 500, "你好"),
                            new TranscriptSegment(500, 1000, "世界")), 1.0);
        }

        @Override
        public LayoutResult layout(LayoutRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TableResult table(TableRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    private RichDocumentParser newParser(StubProvider stub) {
        ParseProperties props = new ParseProperties();
        props.setProvider("stub");
        props.setRetryBackoffBaseMs(0);
        ParseInputValidator validator = new ParseInputValidator(props);
        ProviderRegistry registry = new ProviderRegistry(List.of(stub), props);
        DocumentAiGateway gateway = new DocumentAiGateway(registry, new ProviderExecutor(props), props);
        TikaTextExtractor tika = new TikaTextExtractor();
        return new RichDocumentParser(validator, gateway, new DocumentParser(tika), tika,
                new PageConflictDetector(props));
    }

    // ===== PDF =====

    @Test
    void textLayerPdfParsesWithoutOcrEvenWhenOcrUnavailable() {
        StubProvider stub = new StubProvider();
        stub.available = false; // 模拟 provider=none / 无 Key
        RichDocumentParser parser = newParser(stub);

        ParsedDocument doc = parser.parse("d1", "a.pdf", textPdf(PDF_TEXT), null);

        assertThat(doc.kind()).isEqualTo(DocKind.PDF);
        assertThat(doc.aiSkipped()).isFalse();
        assertThat(doc.fullText()).contains("12345678");
        assertThat(doc.pages()).hasSize(1);
        assertThat(doc.pages().get(0).scanned()).isFalse();
        assertThat(stub.ocrCalls).isZero();
    }

    @Test
    void scannedBlankPdfTriggersPerPageOcrWithPageNoAndConfidence() {
        StubProvider stub = new StubProvider();
        RichDocumentParser parser = newParser(stub);

        ParsedDocument doc = parser.parse("d2", "scan.pdf", blankPdf(), null);

        assertThat(doc.aiSkipped()).isFalse();
        assertThat(doc.pages()).hasSize(1);
        assertThat(doc.pages().get(0).scanned()).isTrue();
        assertThat(doc.pages().get(0).ocr()).isNotNull();
        assertThat(doc.pages().get(0).ocr().spans().get(0).confidence()).isEqualTo(0.91);
        assertThat(doc.fullText()).isEqualTo(OCR_TEXT);
        assertThat(stub.ocrCalls).isEqualTo(1);
    }

    @Test
    void fullyScannedPdfWithoutOcrIsAiSkippedNotSilentlyEmpty() {
        StubProvider stub = new StubProvider();
        stub.available = false;
        RichDocumentParser parser = newParser(stub);

        ParsedDocument doc = parser.parse("d3", "scan.pdf", blankPdf(), null);

        assertThat(doc.aiSkipped()).isTrue();
        assertThat(doc.kind()).isEqualTo(DocKind.PDF);
        assertThat(doc.pages().get(0).scanned()).isTrue();
        assertThat(doc.pages().get(0).ocr()).isNull();
    }

    @Test
    void encryptedPdfWithoutPasswordRequestsDecryptThenResumesWithCorrectOne() {
        StubProvider stub = new StubProvider();
        RichDocumentParser parser = newParser(stub);
        byte[] pdf = encryptedPdf("owner-secret", "user-secret", PDF_TEXT);

        Throwable noPwd = catchThrowable(() -> parser.parse("d4", "secret.pdf", pdf, null));
        assertThat(noPwd).isInstanceOf(EncryptedDocumentException.class);
        assertThat(((EncryptedDocumentException) noPwd).passwordRejected()).isFalse();

        Throwable wrong = catchThrowable(() -> parser.parse("d4", "secret.pdf", pdf, "bad"));
        assertThat(wrong).isInstanceOf(EncryptedDocumentException.class);
        assertThat(((EncryptedDocumentException) wrong).passwordRejected()).isTrue();

        ParsedDocument ok = parser.parse("d4", "secret.pdf", pdf, "user-secret");
        assertThat(ok.aiSkipped()).isFalse();
        assertThat(ok.fullText()).contains("12345678");
    }

    @Test
    void corruptPdfIsIllegalStateNotRetryLoop() {
        RichDocumentParser parser = newParser(new StubProvider());

        Throwable t = catchThrowable(() ->
                parser.parse("d5", "broken.pdf", "not a real pdf content".getBytes(), null));

        assertThat(t).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PDF 解析失败");
    }

    // ===== 图片 / 录音 =====

    @Test
    void imageRoutesToOcrAndSkipsWhenUnavailable() {
        byte[] png = tinyPng();

        ParsedDocument withOcr = newParser(new StubProvider()).parse("d6", "shot.png", png, null);
        assertThat(withOcr.kind()).isEqualTo(DocKind.IMAGE);
        assertThat(withOcr.pages().get(0).pageNo()).isEqualTo(1);
        assertThat(withOcr.fullText()).isEqualTo(OCR_TEXT);

        StubProvider off = new StubProvider();
        off.available = false;
        ParsedDocument skipped = newParser(off).parse("d6", "shot.png", png, null);
        assertThat(skipped.aiSkipped()).isTrue();
        assertThat(skipped.fullText()).isEmpty();
    }

    @Test
    void audioRoutesToAsrWithTimelineAndSkipsWhenUnavailable() {
        StubProvider stub = new StubProvider();
        RichDocumentParser parser = newParser(stub);

        ParsedDocument audio = parser.parse("d7", "talk.mp3", new byte[]{1, 2, 3}, null);
        assertThat(audio.kind()).isEqualTo(DocKind.AUDIO);
        assertThat(audio.aiSkipped()).isFalse();
        assertThat(audio.transcript().segments()).hasSize(2);
        assertThat(audio.transcript().segments().get(1).endMs()).isEqualTo(1000L);
        assertThat(audio.hasTimeline()).isTrue();
        assertThat(stub.asrCalls).isEqualTo(1);

        StubProvider off = new StubProvider();
        off.available = false;
        ParsedDocument skipped = newParser(off).parse("d7", "talk.wav", new byte[]{1}, null);
        assertThat(skipped.aiSkipped()).isTrue();
    }

    // ===== 旧版 .doc（Tika 固定样本）=====

    @Test
    void legacyDocParsedByTika(@TempDir Path tmp) throws Exception {
        // 复用提交的固定 OLE2 样本
        byte[] bytes;
        try (var in = getClass().getResourceAsStream("/fixtures/parse/legacy.doc")) {
            assertThat(in).isNotNull();
            bytes = in.readAllBytes();
        }
        // 固定样本复制到临时目录仅为校验文件可读，解析直接走字节
        Files.write(tmp.resolve("legacy.doc"), bytes);

        RichDocumentParser parser = newParser(new StubProvider());
        ParsedDocument doc = parser.parse("d8", "legacy.doc", bytes, null);

        assertThat(doc.kind()).isEqualTo(DocKind.OFFICE_LEGACY);
        assertThat(doc.fullText()).contains("旧版 Word 文档测试内容");
    }

    @Test
    void legacyXlsGeneratedByHssfParsedByTika() throws Exception {
        // poi-scratchpad（Tika 传递依赖）运行时确定性生成旧版 .xls
        byte[] bytes;
        try (var wb = new org.apache.poi.hssf.usermodel.HSSFWorkbook()) {
            var sheet = wb.createSheet("表一");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue("旧版表格标题");
            row.createCell(1).setCellValue(42);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            bytes = out.toByteArray();
        }

        ParsedDocument doc = newParser(new StubProvider()).parse("d9", "legacy.xls", bytes, null);

        assertThat(doc.kind()).isEqualTo(DocKind.OFFICE_LEGACY);
        assertThat(doc.fullText()).contains("旧版表格标题").contains("42");
    }

    // ===== 样本生成 =====

    private byte[] textPdf(String text) {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] blankPdf() {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] encryptedPdf(String owner, String user, String text) {
        byte[] inner = textPdf(text);
        try (PDDocument doc = Loader.loadPDF(inner)) {
            AccessPermission perms = new AccessPermission();
            StandardProtectionPolicy policy = new StandardProtectionPolicy(owner, user, perms);
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] tinyPng() {
        try {
            BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
