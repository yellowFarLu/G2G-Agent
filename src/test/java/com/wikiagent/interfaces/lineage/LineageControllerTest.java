package com.wikiagent.interfaces.lineage;

import com.wikiagent.application.lineage.ProvenanceService;
import com.wikiagent.domain.extract.ExtractedFieldValue;
import com.wikiagent.domain.extract.FieldSource;
import com.wikiagent.domain.extract.FieldValueType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C3 血缘/版本/diff REST API 测试：3 版本字段 diff + 字段历史链。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
class LineageControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ProvenanceService provenance;

    private ExtractedFieldValue fv(String key, String value, double conf) {
        return new ExtractedFieldValue(key, value, FieldValueType.STRING, conf,
                FieldSource.MODEL, true, List.of(), List.of());
    }

    @Test
    void threeVersionFieldDiffAndLineageEndpoints() throws Exception {
        String docId = "doc-api-" + System.nanoTime();
        // 3 个文档版本：amount 100 → 120 → 120；seller v3 人工改
        provenance.createDocVersion(docId, null, "首次解析", null, "system");
        provenance.upsertField(docId, 1, fv("amount", "100", 0.9), "invoice-demo", "1.0", "金额", false);
        provenance.upsertField(docId, 1, fv("seller", "北京云智", 0.95), "invoice-demo", "1.0", "销售方", false);
        provenance.createDocVersion(docId, 1, "重解析", null, "system");
        provenance.upsertField(docId, 2, fv("amount", "120", 0.95), "invoice-demo", "1.0", "金额", false);
        provenance.upsertField(docId, 2, fv("seller", "北京云智", 0.95), "invoice-demo", "1.0", "销售方", false);
        provenance.createDocVersion(docId, 2, "人工修订", null, "reviewer");
        provenance.upsertField(docId, 3, fv("amount", "120", 0.95), "invoice-demo", "1.0", "金额", false);
        provenance.upsertField(docId, 3, fv("seller", "上海云智", 1.0), "invoice-demo", "1.0", "销售方", true);

        // versions 列表：3 个版本，旧版 SUPERSEDED
        mvc.perform(get("/api/documents/" + docId + "/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        // lineage 含字段
        mvc.perform(get("/api/documents/" + docId + "/lineage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docId").value(docId))
                .andExpect(jsonPath("$.fields.length()").value(2));

        // 字段历史链：按文档版本号对齐
        mvc.perform(get("/api/documents/" + docId + "/fields/amount/lineage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history.length()").value(3))
                .andExpect(jsonPath("$.history[0].valueText").value("100"))
                .andExpect(jsonPath("$.history[1].valueText").value("120"))
                .andExpect(jsonPath("$.history[2].valueText").value("120"));

        // v1 vs v2 diff：amount 变更、seller 不变（带 source）
        mvc.perform(get("/api/documents/" + docId + "/versions/1/diff/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionA").value(1))
                .andExpect(jsonPath("$.versionB").value(2))
                .andExpect(jsonPath("$.fields[?(@.fieldKey=='amount')].changed").value(true))
                .andExpect(jsonPath("$.fields[?(@.fieldKey=='amount')].valueA").value("100"))
                .andExpect(jsonPath("$.fields[?(@.fieldKey=='amount')].valueB").value("120"))
                .andExpect(jsonPath("$.fields[?(@.fieldKey=='seller')].changed").value(false))
                .andExpect(jsonPath("$.fields[?(@.fieldKey=='seller')].sourceA").value("MODEL"));

        // v2 vs v3 diff：seller 人工修订（source=HUMAN + editedBy），amount 不变
        mvc.perform(get("/api/documents/" + docId + "/versions/2/diff/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[?(@.fieldKey=='seller')].changed").value(true))
                .andExpect(jsonPath("$.fields[?(@.fieldKey=='seller')].sourceB").value("HUMAN"))
                .andExpect(jsonPath("$.fields[?(@.fieldKey=='seller')].editedByB").value("human"))
                .andExpect(jsonPath("$.fields[?(@.fieldKey=='amount')].changed").value(false));

        // 不存在的版本 404
        mvc.perform(get("/api/documents/" + docId + "/versions/1/diff/9"))
                .andExpect(status().isNotFound());
    }
}
