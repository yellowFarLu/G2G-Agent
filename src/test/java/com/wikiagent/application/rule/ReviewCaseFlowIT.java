package com.wikiagent.application.rule;

import com.wikiagent.application.lineage.ProvenanceService;
import com.wikiagent.domain.lineage.EdgeType;
import com.wikiagent.domain.rule.ReviewCase;
import com.wikiagent.domain.rule.RuleComputation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-D2 三类处置留痕：materialDiff 自动建案；EDIT → 字段更新 + EDITED/REVIEWED 边；
 * APPROVE/REJECT → resolution_json + REVIEWED 边。
 */
@SpringBootTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
class ReviewCaseFlowIT {

    @Autowired
    private RuleSetService ruleSetService;

    @Autowired
    private RuleExecutionService executionService;

    @Autowired
    private ReviewCaseService reviewCaseService;

    @Autowired
    private ProvenanceService provenance;

    @Test
    void materialDiffAutoCreatesCaseAndEditDisposes() {
        String code = "matdiff-" + System.nanoTime();
        String docId = "doc-md-" + System.nanoTime();
        String dsl = """
                {"steps":[
                  {"op":"materialDiff","params":{"left":"matA","right":"matB"},"to":"diff"}
                ],"outputs":["diff"]}
                """;
        ruleSetService.createDraft(code, dsl, "材料比对", "test");
        ruleSetService.publish(code, 1);

        Map<String, Object> input = Map.of(
                "matA", Map.of("amount", "100", "tax", "13"),
                "matB", Map.of("amount", "120", "tax", "13"));
        RuleComputation comp = executionService.execute(code, docId, input);
        assertThat(comp.status()).isEqualTo(RuleComputation.ComputationStatus.SUCCESS);

        // 自动建 MATERIAL_DIFF 案件
        List<ReviewCase> cases = reviewCaseService.list(null, docId);
        assertThat(cases).anyMatch(c -> c.caseType() == ReviewCase.ReviewCaseType.MATERIAL_DIFF
                && c.ruleCode().equals(code));
        ReviewCase mdCase = cases.stream()
                .filter(c -> c.caseType() == ReviewCase.ReviewCaseType.MATERIAL_DIFF)
                .findFirst().orElseThrow();
        assertThat(mdCase.diffJson()).contains("\"match\":false");
    }

    @Test
    void editActionUpdatesFieldAndEdges() {
        String docId = "doc-edit-" + System.nanoTime();
        String fieldKey = "seller";
        // 先落一个 MODEL 字段
        provenance.upsertField(docId, 1,
                new com.wikiagent.domain.extract.ExtractedFieldValue(fieldKey, "旧名称",
                        com.wikiagent.domain.extract.FieldValueType.STRING, 0.5,
                        com.wikiagent.domain.extract.FieldSource.MODEL, false,
                        List.of("低置信"), List.of()),
                "test-schema", "1.0", fieldKey, false);

        ReviewCase c = reviewCaseService.createLowConfidence(docId, 1, fieldKey, "MODEL", 0.5,
                "低置信", null);
        ReviewCase disposed = reviewCaseService.dispose(c.id(), ReviewCase.ReviewAction.EDIT,
                Map.of(fieldKey, "新名称"), "reviewer-1");

        assertThat(disposed.status()).isEqualTo(ReviewCase.ReviewCaseStatus.EDITED);
        assertThat(disposed.resolutionJson()).contains("新名称").contains("reviewer-1");

        // 字段值更新 + HUMAN 来源
        var fields = provenance.fieldHistory(docId, fieldKey);
        assertThat(fields).isNotEmpty();
        var latest = fields.get(fields.size() - 1);
        assertThat(latest.valueText()).isEqualTo("新名称");
        assertThat(latest.source()).isEqualTo(com.wikiagent.domain.extract.FieldSource.HUMAN);

        // EDITED + REVIEWED 边
        var edges = provenance.edgesForDoc(docId);
        assertThat(edges).anyMatch(e -> e.edgeType() == EdgeType.EDITED && e.toRef().equals(fieldKey));
        assertThat(edges).anyMatch(e -> e.edgeType() == EdgeType.REVIEWED
                && e.fromRef().equals(String.valueOf(c.id())));
    }

    @Test
    void approveAndRejectLeaveResolutionAndEdge() {
        String docId = "doc-ar-" + System.nanoTime();
        ReviewCase c1 = reviewCaseService.createLowConfidence(docId, 1, "f1", "MODEL", 0.4, "低", null);
        ReviewCase c2 = reviewCaseService.createLowConfidence(docId, 1, "f2", "MODEL", 0.3, "低", null);

        ReviewCase approved = reviewCaseService.dispose(c1.id(), ReviewCase.ReviewAction.APPROVE,
                null, "reviewer-a");
        ReviewCase rejected = reviewCaseService.dispose(c2.id(), ReviewCase.ReviewAction.REJECT,
                null, "reviewer-b");

        assertThat(approved.status()).isEqualTo(ReviewCase.ReviewCaseStatus.APPROVED);
        assertThat(approved.resolutionJson()).contains("APPROVE").contains("reviewer-a");
        assertThat(rejected.status()).isEqualTo(ReviewCase.ReviewCaseStatus.REJECTED);
        assertThat(rejected.resolutionJson()).contains("REJECT").contains("reviewer-b");

        var edges = provenance.edgesForDoc(docId);
        assertThat(edges).anyMatch(e -> e.edgeType() == EdgeType.REVIEWED
                && e.note().contains("APPROVE"));
        assertThat(edges).anyMatch(e -> e.edgeType() == EdgeType.REVIEWED
                && e.note().contains("REJECT"));
    }

    /**
     * #8 材料差异重算幂等：同输入连续执行两次（mismatch 不变）只建一条 OPEN 案件；
     * 人工处置后再次执行（新一轮计算）产生新的 OPEN 案件。
     */
    @Test
    void materialDiffIsIdempotentWhileOpenAndReopensAfterDispose() {
        String code = "matdiff-idem-" + System.nanoTime();
        String docId = "doc-md-idem-" + System.nanoTime();
        String dsl = """
                {"steps":[
                  {"op":"materialDiff","params":{"left":"matA","right":"matB"},"to":"diff"}
                ],"outputs":["diff"]}
                """;
        ruleSetService.createDraft(code, dsl, "材料比对幂等", "test");
        ruleSetService.publish(code, 1);

        Map<String, Object> input = Map.of(
                "matA", Map.of("amount", "100", "tax", "13"),
                "matB", Map.of("amount", "120", "tax", "13"));

        executionService.execute(code, docId, input);
        executionService.execute(code, docId, input);

        List<ReviewCase> afterTwice = reviewCaseService.list(null, docId);
        List<ReviewCase> openMaterial = afterTwice.stream()
                .filter(c -> c.caseType() == ReviewCase.ReviewCaseType.MATERIAL_DIFF
                        && c.status() == ReviewCase.ReviewCaseStatus.OPEN)
                .toList();
        assertThat(openMaterial).as("同输入重算不得重复建 MATERIAL_DIFF OPEN 案件").hasSize(1);

        // 人工处置（APPROVE）后案件关闭
        reviewCaseService.dispose(openMaterial.get(0).id(),
                ReviewCase.ReviewAction.APPROVE, null, "reviewer-1");

        // 新一轮计算 mismatch 仍在 → 产生新 OPEN 案件
        executionService.execute(code, docId, input);
        List<ReviewCase> reopened = reviewCaseService.list(null, docId).stream()
                .filter(c -> c.caseType() == ReviewCase.ReviewCaseType.MATERIAL_DIFF)
                .toList();
        assertThat(reopened).hasSize(2);
        assertThat(reopened.stream().filter(c -> c.status() == ReviewCase.ReviewCaseStatus.OPEN))
                .hasSize(1);
        assertThat(reopened.stream().filter(c -> c.status() == ReviewCase.ReviewCaseStatus.APPROVED))
                .hasSize(1);
    }
}
