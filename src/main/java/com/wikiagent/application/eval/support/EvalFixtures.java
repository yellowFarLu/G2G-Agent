package com.wikiagent.application.eval.support;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;

/**
 * 离线评测合成夹具：用 PDFBox/ImageIO 在运行时确定性生成二进制输入，
 * 与 {@code RichDocumentParserTest} 同一做法——不提交二进制固件、无随机/时钟依赖。
 * <p>
 * 注意：PDF 标准 14 字体（HELVETICA）只能渲染 Latin 文本，故 PDF 文本层用英文；
 * 中文断言词由录制 AI 桩（OCR/TABLE）或既有 Tika 固件提供。
 */
public final class EvalFixtures {

    /** synthetic://pdf/invoice-text —— 文本层 PDF（customs 报关要素）。 */
    public static final String SYN_INVOICE_TEXT = "synthetic://pdf/invoice-text";
    /** synthetic://pdf/blank-scan —— 空白扫描 PDF（触发逐页 OCR）。 */
    public static final String SYN_BLANK_SCAN = "synthetic://pdf/blank-scan";
    /** synthetic://pdf/encrypted —— 用户口令 eval-secret 的加密 PDF。 */
    public static final String SYN_ENCRYPTED = "synthetic://pdf/encrypted";
    /** synthetic://pdf/table-2pages —— 两页续表 PDF（跨页拼接样本，文本层英文）。 */
    public static final String SYN_TABLE_2PAGES = "synthetic://pdf/table-2pages";
    /** synthetic://image/png —— 4x4 PNG（触发图片 OCR）。 */
    public static final String SYN_IMAGE_PNG = "synthetic://image/png";
    /** synthetic://audio/mp3 —— 3 字节合成音频（触发 ASR）。 */
    public static final String SYN_AUDIO_MP3 = "synthetic://audio/mp3";

    public static final String ENCRYPTED_USER_PASSWORD = "eval-secret";

    private static final Map<String, byte[]> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private EvalFixtures() {
    }

    /** 按 synthetic:// URI 解析为确定性字节；未知 URI 抛 IllegalArgumentException。 */
    public static byte[] synthetic(String uri) {
        return CACHE.computeIfAbsent(uri, EvalFixtures::build);
    }

    private static byte[] build(String uri) {
        try {
            return switch (uri) {
                case SYN_INVOICE_TEXT -> textPdf(
                        "INVOICE NO 12345678 CUSTOMS DECLARATION TOTAL 99.00 USD", 1);
                case SYN_BLANK_SCAN -> blankPdf(1);
                case SYN_ENCRYPTED -> encryptedPdf(
                        "INDUSTRY ACCESS NEGATIVE LIST 2026 ENTRY FILING");
                case SYN_TABLE_2PAGES -> twoPageTablePdf();
                case SYN_IMAGE_PNG -> tinyPng();
                case SYN_AUDIO_MP3 -> new byte[]{1, 2, 3};
                default -> throw new IllegalArgumentException("未知合成夹具: " + uri);
            };
        } catch (Exception e) {
            throw new IllegalStateException("生成合成夹具失败: " + uri, e);
        }
    }

    static byte[] textPdf(String text) {
        return textPdf(text, 1);
    }

    static byte[] textPdf(String text, int pages) {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText(text + (pages > 1 ? " PAGE" + (i + 1) : ""));
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static byte[] blankPdf(int pages) {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 两页续表 PDF：每页英文文本层（golden 文本断言用），
     * 中文表格内容由 {@link OfflineEvalAiProvider#table} 录制返回（两页含重复表头，
     * 由 TableStitcher 去重一行 → 全局 3 行）。
     */
    static byte[] twoPageTablePdf() {
        try (PDDocument doc = new PDDocument()) {
            for (int pageNo = 1; pageNo <= 2; pageNo++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText(pageNo == 1 ? "CROSS TABLE PAGE ONE" : "CROSS TABLE PAGE TWO");
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static byte[] encryptedPdf(String text) {
        byte[] inner = textPdf(text);
        try (PDDocument doc = Loader.loadPDF(inner)) {
            StandardProtectionPolicy policy = new StandardProtectionPolicy(
                    "eval-owner", ENCRYPTED_USER_PASSWORD, new AccessPermission());
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static byte[] tinyPng() {
        try {
            BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 超大样本：恰好 2MB（配合 maxFileMb=1 的校验器）。 */
    public static byte[] oversizeBytes() {
        return new byte[2 * 1024 * 1024];
    }
}
