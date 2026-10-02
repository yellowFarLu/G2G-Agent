package com.wikiagent.application.rule;

import com.wikiagent.application.lineage.ProvenanceService;
import com.wikiagent.domain.lineage.EdgeType;
import com.wikiagent.domain.lineage.FieldVersion;
import com.wikiagent.domain.rule.ReviewCase;
import com.wikiagent.domain.rule.RuleComputation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    /**
     * #10 diff_json（reason 含换行/引号/反斜杠）与处置后的 resolution_json
     * 必须都是 Jackson 可读回的合法 JSON，且内容无损。
     */
    @Test
    void lowConfidenceDiffJsonIsValidJsonWithSpecialChars() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String docId = "doc-json-" + System.nanoTime();
        String tricky = "模型说：\"值不对\"\n第二行\\路径\tend";

        ReviewCase created = reviewCaseService.createLowConfidence(docId, 1, "f1",
                "MODEL", 0.2, tricky, null);

        ReviewCase reloaded = reviewCaseService.list(null, docId).stream()
                .filter(c -> c.id().equals(created.id())).findFirst().orElseThrow();
        JsonNode diffNode = mapper.readTree(reloaded.diffJson());
        assertThat(diffNode.get("reason").asText()).isEqualTo(tricky);

        // null reason 也必须是合法 JSON（{"reason":null}），不产生裸 null 列歧义
        ReviewCase nullReason = reviewCaseService.createLowConfidence(docId, 1, "f2",
                "MODEL", 0.1, null, null);
        JsonNode nullNode = mapper.readTree(
                reviewCaseService.list(null, docId).stream()
                        .filter(c -> c.id().equals(nullReason.id())).findFirst().orElseThrow()
                        .diffJson());
        assertThat(nullNode.get("reason").isNull()).isTrue();

        ReviewCase disposed = reviewCaseService.dispose(created.id(),
                ReviewCase.ReviewAction.REJECT, null, "reviewer-x");
        JsonNode resolutionNode = mapper.readTree(disposed.resolutionJson());
        assertThat(resolutionNode.get("action").asText())
                .isEqualTo(ReviewCase.ReviewAction.REJECT.name());
    }

    /**
     * #12 EDIT 版本锚定：案件 versionNo=1，处置时文档已产生 v2，人工编辑必须
     * 写回 v1 的 field_version，EDITED/REVIEWED 边也落在 v1，不得污染 v2 历史。
     */
    @Test
    void editIsAnchoredToCaseVersionNotLatest() {
        String docId = "doc-anchor-" + System.nanoTime();
        provenance.createDocVersion(docId, null, "初版", null, "test");
        ReviewCase c = reviewCaseService.createLowConfidence(docId, 1, "f1",
                "MODEL", 0.2, "低置信", null);

        // 复核期间文档演进到 v2（最新版本已是 2）
        provenance.createDocVersion(docId, 1, "复核期间新版本", null, "test");
        assertThat(provenance.latestVersion(docId).versionNo()).isEqualTo(2);

        reviewCaseService.dispose(c.id(), ReviewCase.ReviewAction.EDIT,
                Map.of("f1", "人工锚定值"), "reviewer-1");

        List<FieldVersion> history = provenance.fieldHistory(docId, "f1");
        assertThat(history).as("字段历史只应新增锚定 v1 的一行").hasSize(1);
        assertThat(history.get(0).versionNo()).isEqualTo(1);
        assertThat(history.get(0).valueText()).isEqualTo("人工锚定值");
        assertThat(history.get(0).editedBy()).isEqualTo("human");

        var edges = provenance.edgesForDoc(docId);
        assertThat(edges).anyMatch(e -> e.edgeType() == EdgeType.EDITED && e.versionNo() == 1);
        assertThat(edges).anyMatch(e -> e.edgeType() == EdgeType.REVIEWED && e.versionNo() == 1);
        assertThat(edges).as("处置血缘不得写到处置期间产生的 v2").noneMatch(e -> e.versionNo() == 2);
    }
}
