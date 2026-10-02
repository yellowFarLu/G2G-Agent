package com.wikiagent.application.task.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.application.extract.FieldExtractionService;
import com.wikiagent.application.parse.ParseInputValidator;
import com.wikiagent.domain.extract.ExtractedFieldValue;
import com.wikiagent.domain.extract.ExtractionReport;
import com.wikiagent.domain.extract.FieldValueType;
import com.wikiagent.domain.task.HumanRequiredException;
import com.wikiagent.domain.task.HumanTaskKind;
import com.wikiagent.domain.task.StepDef;
import com.wikiagent.domain.task.StepResult;
import com.wikiagent.domain.task.TaskExecutionContext;
import com.wikiagent.entity.KbDocument;
import com.wikiagent.repo.KbDocumentRepo;
import com.wikiagent.service.ingest.IngestionService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 子项目 B5：INGEST 条件步骤分流测试——
 * planSteps 按介质/参数动态注册；加密 PDF 转 DECRYPT 人工；
 * AI 不可用介质终态 AI_SKIPPED；抽取需复核转 REVIEW。
 */
@SpringBootTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
class IngestRichPipelineTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private IngestionService ingestion;

    @Autowired
    private KbDocumentRepo docRepo;

    @Autowired
    private com.wikiagent.repo.KbChildChunkRepo childChunkRepo;

    @Autowired
    private ParseInputValidator validator;

    @MockBean
    private FieldExtractionService extractionService;

    @MockBean
    private com.wikiagent.application.parse.DocumentAiGateway gateway;

    @MockBean
    private org.springframework.ai.embedding.EmbeddingModel embeddingModel;

    @MockBean
    private com.wikiagent.service.store.MilvusStoreService milvus;

    private final List<String> createdDocIds = new ArrayList<>();

    private IngestTaskHandler handler() {
        return new IngestTaskHandler(ingestion, validator);
    }

    @AfterEach
    void cleanup() {
        for (String docId : createdDocIds) {
            try {
                ingestion.delete(docId);
            } catch (Exception ignored) {
                // 忽略清理失败
            }
        }
        createdDocIds.clear();
    }

    private String newDoc(String filename, byte[] bytes) throws Exception {
        String docId = "doc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Path dir = Path.of("data", "uploads", docId);
        Files.createDirectories(dir);
        Files.write(dir.resolve(filename), bytes);
        createdDocIds.add(docId);
        docRepo.save(newDocRow(docId, filename));
        return docId;
    }

    private com.fasterxml.jackson.databind.JsonNode args(String docId, String filename,
                                                          String extractionSchema) {
        var node = JSON.createObjectNode()
                .put("docId", docId)
                .put("filename", filename)
                .put("userId", "user-1");
        if (extractionSchema != null) {
            node.put("extractionSchema", extractionSchema);
        }
        return node;
    }

    private StepResult runStep(String taskId, com.fasterxml.jackson.databind.JsonNode args,
                               int stepNo, Map<String, com.fasterxml.jackson.databind.JsonNode> inputs)
            throws Exception {
        TaskExecutionContext ctx = new TaskExecutionContext(taskId, "INGEST", args, inputs, 0);
        ctx.setCurrentStepNo(stepNo);
        return handler().executeStep(ctx);
    }

    private byte[] samplePdf(String text) throws Exception {
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
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        }
    }

    @Test
    void planStepsAddsLayoutForPdfAndExtractWhenSchemaProvided() {
        var pdfArgs = args("x", "a.pdf", "invoice-demo");
        List<StepDef> steps = handler().planSteps(pdfArgs);
        assertThat(steps).extracting(StepDef::no)
                .contains(1, 2, 3, 4, 5, 6, IngestTaskHandler.STEP_LAYOUT_TABLE, IngestTaskHandler.STEP_EXTRACT);

        var textArgs = args("x", "a.txt", null);
        List<StepDef> textSteps = handler().planSteps(textArgs);
        assertThat(textSteps).extracting(StepDef::no)
                .containsExactly(1, 2, 3, 4, 5, 6);
    }

    @Test
    void encryptedPdfRaisesDecryptHumanTask() throws Exception {
        byte[] encrypted;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("Encrypted invoice document content for decrypt test. ".repeat(3));
                cs.endText();
            }
            StandardProtectionPolicy policy = new StandardProtectionPolicy(
                    "owner-secret", "user-open", new AccessPermission());
            doc.protect(policy);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            encrypted = bos.toByteArray();
        }
        String docId = newDoc("enc.pdf", encrypted);
        String taskId = "tsk_enc_" + uid();
        var a = args(docId, "enc.pdf", null);

        // 未提供口令 → DECRYPT 人工任务
        assertThatThrownBy(() -> runStep(taskId, a, 2, Map.of()))
                .isInstanceOf(HumanRequiredException.class)
                .satisfies(e -> {
                    HumanRequiredException h = (HumanRequiredException) e;
                    assertThat(h.getKind()).isEqualTo(HumanTaskKind.DECRYPT);
                    assertThat(h.getFormSchema().toString()).contains("decryptPassword");
                });

        // 提供错误口令 → 仍 DECRYPT（passwordRejected=true 提示）
        var inputs = Map.<String, com.fasterxml.jackson.databind.JsonNode>of(
                "decryptPassword", JSON.getNodeFactory().textNode("wrong"));
        assertThatThrownBy(() -> runStep(taskId, a, 2, inputs))
                .isInstanceOf(HumanRequiredException.class)
                .satisfies(e -> assertThat(((HumanRequiredException) e).getInstruction()).contains("口令"));

        // 正确口令 → PARSE 成功
        var correct = Map.<String, com.fasterxml.jackson.databind.JsonNode>of(
                "decryptPassword", JSON.getNodeFactory().textNode("user-open"));
        StepResult result = runStep(taskId, a, 2, correct);
        assertThat(result.skip()).isFalse();
        assertThat(Files.readString(Path.of("data", "uploads", docId, "_parsed.json"))).isNotEmpty();
    }

    @Test
    void imageWithoutOcrProviderTerminatesAiSkipped() throws Exception {
        // 1x1 PNG
        byte[] png = new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52,
                0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 0x1F, 0x15, (byte) 0xC4, (byte) 0x89,
                0, 0, 0, 0x0D, 0x49, 0x44, 0x41, 0x54,
                0x78, (byte) 0x9C, 0x63, 0, 1, 0, 0, 5, 0, 1, 0x0D, 0x0A, 0x2D, (byte) 0xB4,
                0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE, 0x42, 0x60, (byte) 0x82};
        String docId = newDoc("scan.png", png);
        var a = args(docId, "scan.png", null);

        // 驱动 PARSE→VERIFY 全步骤：AI_SKIPPED 后 CLEAN/SPLIT/EMBED 必须短路，
        // 不得因空文本在 SPLIT 抛错导致任务 FAILED；VERIFY 正常收尾但不置 READY
        for (int step = 2; step <= 5; step++) {
            StepResult r = runStep("tsk_img_" + uid() + "_" + step, a, step, Map.of());
            assertThat(r.skip()).as("step %d 应短路跳过", step).isTrue();
        }
        StepResult verified = runStep("tsk_img_" + uid(), a, 6, Map.of());
        assertThat(verified.skip()).isFalse();
        assertThat(docRepo.findById(docId).orElseThrow().getStatus())
                .isEqualTo(KbDocument.AI_SKIPPED);
        // 无子块产生（不静默空入库）
        assertThat(childRepo(docId)).isNullOrEmpty();
    }

    private java.util.List<com.wikiagent.entity.KbChildChunk> childRepo(String docId) {
        return childChunkRepo.findByDocId(docId);
    }

    private byte[] onePixelPng() {
        return new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52,
                0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 0x1F, 0x15, (byte) 0xC4, (byte) 0x89,
                0, 0, 0, 0x0D, 0x49, 0x44, 0x41, 0x54,
                0x78, (byte) 0x9C, 0x63, 0, 1, 0, 0, 5, 0, 1, 0x0D, 0x0A, 0x2D, (byte) 0xB4,
                0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE, 0x42, 0x60, (byte) 0x82};
    }

    /** fix6：OCR 成功路径 handler 级 IT——图片经 OCR 有文本，全程到 READY（AI_SKIPPED 的反向用例）。 */
    @Test
    void imageWithOcrProviderFlowsToReady() throws Exception {
        String docId = newDoc("scan-ok.png", onePixelPng());
        var a = args(docId, "scan-ok.png", null);

        when(gateway.has(com.wikiagent.domain.parse.spi.Capability.OCR)).thenReturn(true);
        when(gateway.ocrOptional(any(com.wikiagent.domain.parse.spi.OcrRequest.class)))
                .thenReturn(java.util.Optional.of(new com.wikiagent.domain.parse.spi.OcrResult(
                        "OCR 识别的发票文本内容，用于知识库检索。发票号码：12345678。",
                        List.of(), 0.92)));
        when(embeddingModel.embed(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> new float[8]).toList();
        });

        StepResult parse = runStep("tsk_ocr_" + uid() + "_2", a, 2, Map.of());
        assertThat(parse.skip()).isFalse(); // OCR 成功：不 AI_SKIPPED

        for (int step = 3; step <= 5; step++) {
            StepResult r = runStep("tsk_ocr_" + uid() + "_" + step, a, step, Map.of());
            assertThat(r.skip()).as("step %d 不应跳过", step).isFalse();
        }
        runStep("tsk_ocr_" + uid() + "_6", a, 6, Map.of());
        assertThat(docRepo.findById(docId).orElseThrow().getStatus()).isEqualTo(KbDocument.READY);
        assertThat(childRepo(docId)).isNotEmpty();
    }

    @Test
    void extractWithReviewNeedsRaisesReviewHumanTask() throws Exception {
        String docId = newDoc("plain.txt", "发票号码：12345678".getBytes());
        var a = args(docId, "plain.txt", "invoice-demo");
        runStep("tsk_ex_" + uid(), a, 2, Map.of()); // PARSE 写 _parsed.json

        when(extractionService.extract(eq(docId), eq("invoice-demo"), any()))
                .thenReturn(new ExtractionReport("invoice-demo", "1.0",
                        List.of(new ExtractedFieldValue("invoiceNo", "12345678",
                                FieldValueType.STRING, 0.5, com.wikiagent.domain.extract.FieldSource.MODEL,
                                true, List.of(), List.of())),
                        true, List.of("字段 invoiceNo 置信度 0.5 低于阈值 0.75")));

        assertThatThrownBy(() -> runStep("tsk_ex2_" + uid(), a, IngestTaskHandler.STEP_EXTRACT, Map.of()))
                .isInstanceOf(HumanRequiredException.class)
                .satisfies(e -> assertThat(((HumanRequiredException) e).getKind())
                        .isEqualTo(HumanTaskKind.REVIEW));
    }

    @Test
    void extractWithoutReviewSucceeds() throws Exception {
        String docId = newDoc("plain.txt", "发票号码：12345678".getBytes());
        var a = args(docId, "plain.txt", "invoice-demo");
        runStep("tsk_ex3_" + uid(), a, 2, Map.of());

        when(extractionService.extract(eq(docId), eq("invoice-demo"), any()))
                .thenReturn(new ExtractionReport("invoice-demo", "1.0", List.of(), false, List.of()));

        StepResult result = runStep("tsk_ex4_" + uid(), a, IngestTaskHandler.STEP_EXTRACT, Map.of());
        assertThat(result.skip()).isFalse();
        assertThat(Files.readString(Path.of("data", "uploads", docId, "_extracted.json"))).isNotEmpty();
    }

    private static String uid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private KbDocument newDocRow(String docId, String filename) {
        KbDocument doc = new KbDocument();
        doc.setId(docId);
        doc.setFilename(filename);
        doc.setDocType(filename.substring(filename.lastIndexOf('.') + 1));
        doc.setSizeBytes(1024);
        doc.setStatus(KbDocument.PARSING);
        return doc;
    }
}
