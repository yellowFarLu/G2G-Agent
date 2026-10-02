package com.wikiagent.application.task.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.domain.task.FatalTaskException;
import com.wikiagent.domain.task.StepResult;
import com.wikiagent.domain.task.TaskExecutionContext;
import com.wikiagent.entity.KbDocument;
import com.wikiagent.repo.KbChildChunkRepo;
import com.wikiagent.repo.KbDocumentRepo;
import com.wikiagent.service.ingest.IngestionService;
import com.wikiagent.service.store.MilvusStoreService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 11：IngestTaskHandler 六步流水线测试（规格 §7 三个固定回归样本）。
 * 真实 parser/cleaner/splitter + H2；EmbeddingModel/MilvusStoreService/打标用 mock 隔离外部服务。
 */
@SpringBootTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
class IngestTaskHandlerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private IngestionService ingestion;

    @Autowired
    private KbDocumentRepo docRepo;

    @Autowired
    private KbChildChunkRepo childRepo;

    @MockBean
    private MilvusStoreService milvus;

    @MockBean
    private EmbeddingModel embeddingModel;

    @MockBean
    private com.wikiagent.application.knowledge.KnowledgeTaggingService taggingService;

    private final List<String> createdDocIds = new ArrayList<>();

    private IngestTaskHandler handler() {
        return new IngestTaskHandler(ingestion);
    }

    @AfterEach
    void cleanup() {
        for (String docId : createdDocIds) {
            try {
                ingestion.delete(docId);
            } catch (Exception ignored) {
                // 清理失败不影响测试结论
            }
        }
        createdDocIds.clear();
    }

    /** 手工驱动步骤 from..to（模拟 worker 逐步调度）。 */
    private List<StepResult> drive(String taskId, com.fasterxml.jackson.databind.JsonNode args,
                                   int from, int to) throws Exception {
        TaskExecutionContext ctx = new TaskExecutionContext(taskId, "INGEST", args, Map.of(), 0);
        List<StepResult> results = new ArrayList<>();
        for (int n = from; n <= to; n++) {
            ctx.setCurrentStepNo(n);
            results.add(handler().executeStep(ctx));
        }
        return results;
    }

    private String newDoc(String filename, byte[] bytes) throws Exception {
        String docId = "doc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Path dir = Path.of("data", "uploads", docId);
        Files.createDirectories(dir);
        Files.write(dir.resolve(filename), bytes);
        createdDocIds.add(docId);
        return docId;
    }

    private com.fasterxml.jackson.databind.JsonNode args(String docId, String filename) {
        return JSON.createObjectNode()
                .put("docId", docId)
                .put("filename", filename)
                .put("userId", "user-1");
    }

    /** 内存生成单页 PDF（PDFBox 3.x API），文本足够触发切分。 */
    static byte[] samplePdf(String text) throws Exception {
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

    /** 内存生成含表头与数据行的 xlsx（POI）。 */
    static byte[] sampleXlsx() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("knowledge");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("item");
            header.createCell(1).setCellValue("description");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("rule-1");
            row.createCell(1).setCellValue("smoke test content for ingestion split. ".repeat(8));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    // ① 普通 PDF：6 步全 DONE，doc 最终 READY
    @Test
    void pdfIngestsThroughSixSteps() throws Exception {
        when(embeddingModel.embed(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> new float[8]).toList();
        });
        String docId = newDoc("sample.pdf", samplePdf(
                "Task framework ingest pipeline smoke test. ".repeat(20)));
        docRepo.save(newDocRow(docId, "sample.pdf"));

        List<StepResult> results = drive("tsk_pdf_" + uid(), args(docId, "sample.pdf"), 1, 6);

        assertThat(results).hasSize(6).allMatch(r -> !r.skip());
        assertThat(docRepo.findById(docId)).isPresent()
                .get().extracting(KbDocument::getStatus).isEqualTo(KbDocument.READY);
        assertThat(docRepo.findById(docId).orElseThrow().getChildCount()).isGreaterThan(0);
        verify(milvus, times(1)).insertChildren(anyList(), anyList());
    }

    // ② 含表格 xlsx：6 步全 DONE，doc 最终 READY
    @Test
    void xlsxIngestsThroughSixSteps() throws Exception {
        when(embeddingModel.embed(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> new float[8]).toList();
        });
        String docId = newDoc("table.xlsx", sampleXlsx());
        docRepo.save(newDocRow(docId, "table.xlsx"));

        List<StepResult> results = drive("tsk_xlsx_" + uid(), args(docId, "table.xlsx"), 1, 6);

        assertThat(results).hasSize(6).allMatch(r -> !r.skip());
        assertThat(docRepo.findById(docId)).isPresent()
                .get().extracting(KbDocument::getStatus).isEqualTo(KbDocument.READY);
    }

    // ③ 子项目 B：旧版真实 .doc（OLE2 固定样本）经 Tika 兜底，PARSE 成功不再致命
    @Test
    void legacyDocParsedByTika() throws Exception {
        byte[] legacyBytes;
        try (var in = getClass().getResourceAsStream("/fixtures/parse/legacy.doc")) {
            assertThat(in).isNotNull();
            legacyBytes = in.readAllBytes();
        }
        String docId = newDoc("legacy.doc", legacyBytes);
        docRepo.save(newDocRow(docId, "legacy.doc"));

        String taskId = "tsk_doc_" + uid();
        TaskExecutionContext ctx = new TaskExecutionContext(taskId, "INGEST", args(docId, "legacy.doc"), Map.of(), 0);
        ctx.setCurrentStepNo(1);
        handler().executeStep(ctx); // DOWNLOAD 成功
        ctx.setCurrentStepNo(2);
        StepResult parseResult = handler().executeStep(ctx); // PARSE 走 Tika

        assertThat(parseResult.skip()).isFalse();
        String raw = Files.readString(
                Path.of("data", "uploads", docId, "_parsed.txt"), StandardCharsets.UTF_8);
        assertThat(raw).contains("旧版 Word 文档测试内容");
    }

    // ④ 断点重跑：预置前 3 步 DONE，从 SPLIT 续跑 → 子块行数不翻倍、不重复写 Milvus
    @Test
    void rerunFromSplitDoesNotDuplicateChildren() throws Exception {
        when(embeddingModel.embed(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> new float[8]).toList();
        });
        String docId = newDoc("rerun.pdf", samplePdf(
                "Rerun idempotency check for split and embed steps. ".repeat(20)));
        docRepo.save(newDocRow(docId, "rerun.pdf"));
        drive("tsk_r1_" + uid(), args(docId, "rerun.pdf"), 1, 6);
        int childrenBefore = childRepo.findByDocId(docId).size();
        assertThat(childrenBefore).isGreaterThan(0);

        // 重跑：新任务实例，步骤 1-3 预置 DONE（跳过），从 SPLIT 续跑
        drive("tsk_r2_" + uid(), args(docId, "rerun.pdf"), 4, 6);

        assertThat(childRepo.findByDocId(docId)).hasSize(childrenBefore);
        verify(milvus, times(1)).insertChildren(anyList(), anyList());
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
