package com.wikiagent.interfaces.task;

import com.wikiagent.service.ingest.IngestionService;
import com.wikiagent.service.store.MilvusStoreService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 11：上传链路接入任务框架的全链路测试（enabled=true）。
 * POST /api/documents → 返回 taskId → 轮询任务 COMPLETED → doc READY；相同字节重复上传幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class UploadAsyncFlowIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private IngestionService ingestion;

    @MockBean
    private MilvusStoreService milvus;

    @MockBean
    private EmbeddingModel embeddingModel;

    @MockBean
    private com.wikiagent.application.knowledge.KnowledgeTaggingService taggingService;

    private final List<String> createdDocIds = new ArrayList<>();
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

    @AfterEach
    void cleanup() {
        for (String docId : createdDocIds) {
            try {
                ingestion.delete(docId);
            } catch (Exception ignored) {
            }
        }
        createdDocIds.clear();
    }

    private static void await(BooleanSupplier cond, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(150);
        }
        org.junit.jupiter.api.Assertions.fail("等待超时: " + what);
    }

    static byte[] samplePdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("Upload async flow integration sample for task framework. ".repeat(20));
                cs.endText();
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        }
    }

    /** 上传结果：docId + taskId（直接从上传响应捕获，detail 端点视图不含 taskId）。 */
    private record Uploaded(String docId, String taskId) {
    }

    private Uploaded upload(byte[] pdfBytes, String userId) throws Exception {
        var node = json.readTree(mvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "upload-sample.pdf",
                                "application/pdf", pdfBytes))
                        .header("X-User-Id", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(org.hamcrest.Matchers.startsWith("tsk_")))
                .andReturn().getResponse().getContentAsString());
        String docId = node.get("id").asText();
        createdDocIds.add(docId);
        return new Uploaded(docId, node.get("taskId").asText());
    }

    private String taskStatus(String taskId) throws Exception {
        return json.readTree(mvc.perform(get("/api/tasks/" + taskId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("status").asText();
    }

    private boolean completed(String taskId) {
        try {
            return "COMPLETED".equals(taskStatus(taskId));
        } catch (Exception e) {
            return false;
        }
    }

    // ① 上传 → 返回 taskId → 任务异步完成 → doc READY
    @Test
    void uploadCreatesTaskAndCompletes() throws Exception {
        when(embeddingModel.embed(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> new float[8]).toList();
        });
        byte[] pdf = samplePdf();
        Uploaded up = upload(pdf, "flow-user-" + uid());

        await(() -> completed(up.taskId()), "入库任务 COMPLETED");
        mvc.perform(get("/api/documents/" + up.docId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
    }

    // ② 相同字节相同用户重复上传 → 幂等：同一 taskId，不新建文档
    @Test
    void duplicateUploadReusesTask() throws Exception {
        when(embeddingModel.embed(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> new float[8]).toList();
        });
        byte[] pdf = samplePdf();
        String user = "dup-user-" + uid();
        Uploaded first = upload(pdf, user);
        await(() -> completed(first.taskId()), "首次入库 COMPLETED");

        // 相同字节 + 相同用户再次上传：命中 bizKey 幂等，同一 taskId
        mvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "upload-sample.pdf",
                                "application/pdf", pdf))
                        .header("X-User-Id", user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.taskId").value(first.taskId()));
    }

    private static String uid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
