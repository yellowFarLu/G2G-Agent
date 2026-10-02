package com.wikiagent.application.lineage;

import com.wikiagent.application.extract.FieldExtractionService;
import com.wikiagent.domain.extract.ExtractedFieldValue;
import com.wikiagent.domain.extract.ExtractionReport;
import com.wikiagent.domain.extract.FieldSource;
import com.wikiagent.domain.extract.FieldValueType;
import com.wikiagent.domain.lineage.ArtifactType;
import com.wikiagent.domain.lineage.DocVersion;
import com.wikiagent.domain.lineage.EdgeType;
import com.wikiagent.domain.parse.model.DocKind;
import com.wikiagent.domain.parse.model.ParsedDocument;
import com.wikiagent.domain.parse.model.ParsedPage;
import com.wikiagent.entity.KbDocument;
import com.wikiagent.repo.KbDocumentRepo;
import com.wikiagent.service.ingest.IngestionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * C2 产线血缘接线测试：PARSE→CLEAN DERIVED 边、字段 EXTRACTED 边可回溯原文片段、
 * 重解析产生新版本且旧版本 SUPERSEDED。
 */
@SpringBootTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
class LineageWiringTest {

    @Autowired
    private IngestionService ingestion;

    @Autowired
    private ProvenanceService provenance;

    @Autowired
    private KbDocumentRepo docRepo;

    @MockBean
    private FieldExtractionService extractionService;

    private final List<String> docs = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String id : docs) {
            try { ingestion.delete(id); } catch (Exception ignored) {}
        }
        docs.clear();
    }

    private String newDoc(String filename, byte[] bytes) throws Exception {
        String id = "doc-l-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Path dir = Path.of("data", "uploads", id);
        Files.createDirectories(dir);
        Files.write(dir.resolve(filename), bytes);
        KbDocument d = new KbDocument();
        d.setId(id);
        d.setFilename(filename);
        d.setDocType(filename.substring(filename.lastIndexOf('.') + 1));
        d.setSizeBytes(bytes.length);
        d.setStatus(KbDocument.PARSING);
        docRepo.save(d);
        docs.add(id);
        return id;
    }

    @Test
    void parseThenCleanProducesDerivedEdge() throws Exception {
        String docId = newDoc("a.txt", "发票号码：12345678\n金额：1280.50".getBytes(StandardCharsets.UTF_8));
        ingestion.richParseStep(docId, "a.txt",
                "发票号码：12345678\n金额：1280.50".getBytes(StandardCharsets.UTF_8), null);
        ingestion.cleanStep(docId, "发票号码：12345678 金额：1280.50");

        var edges = provenance.edgesForDoc(docId);
        assertThat(edges).anyMatch(e -> e.edgeType() == EdgeType.DERIVED
                && e.note() != null && e.note().contains("清洗"));
        // 产物落盘且 sha256 有效
        assertThat(provenance.latestVersion(docId).versionNo()).isEqualTo(1);
    }

    @Test
    void extractWiresExtractedEdgeWithEvidenceSnippet() throws Exception {
        String docId = newDoc("inv.txt", "发票号码：12345678".getBytes(StandardCharsets.UTF_8));
        var parsed = ingestion.richParseStep(docId, "inv.txt",
                "发票号码：12345678".getBytes(StandardCharsets.UTF_8), null);
        // handler 职责：富解析结果写 _parsed.json（供抽取/版面复用）
        new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValue(Path.of("data", "uploads", docId, "_parsed.json").toFile(), parsed);

        ExtractedFieldValue invoiceNo = new ExtractedFieldValue("invoiceNo", "12345678",
                FieldValueType.STRING, 0.95, FieldSource.MODEL, true, List.of(),
                List.of(new com.wikiagent.domain.extract.FieldEvidence(1, "发票号码：12345678")));
        when(extractionService.extract(eq(docId), eq("invoice-demo"), any()))
                .thenReturn(new ExtractionReport("invoice-demo", "1.0", List.of(invoiceNo), false, List.of()));

        ingestion.extractStep(docId, "invoice-demo");

        var edges = provenance.edgesForDoc(docId);
        assertThat(edges).anyMatch(e -> e.edgeType() == EdgeType.EXTRACTED
                && e.toRef().equals("invoiceNo")
                && e.note() != null && e.note().contains("发票号码"));
        // 字段落库可查
        assertThat(provenance.fieldHistory(docId, "invoiceNo")).hasSize(1);
    }

    @Test
    void reparseCreatesNewVersionAndSupersedesOld() throws Exception {
        String docId = newDoc("r.txt", "v1 内容".getBytes(StandardCharsets.UTF_8));
        // 首次解析 → v1
        ingestion.richParseStep(docId, "r.txt", "v1 内容".getBytes(StandardCharsets.UTF_8), null);
        assertThat(provenance.latestVersion(docId).versionNo()).isEqualTo(1);

        // 重解析：先建 v2（SUPERSEDES v1），再解析写入 v2
        DocVersion v1 = provenance.latestVersion(docId);
        provenance.createDocVersion(docId, v1.versionNo(), "内容更新", "sha-v2", "system");
        ingestion.richParseStep(docId, "r.txt", "v2 新内容".getBytes(StandardCharsets.UTF_8), null);

        assertThat(provenance.latestVersion(docId).versionNo()).isEqualTo(2);
        assertThat(provenance.listVersions(docId))
                .extracting(DocVersion::status)
                .containsExactly(DocVersion.PUBLISHED, DocVersion.SUPERSEDED);
    }
}
