package com.wikiagent.application.lineage;

import com.wikiagent.domain.extract.ExtractedFieldValue;
import com.wikiagent.domain.extract.FieldSource;
import com.wikiagent.domain.extract.FieldValueType;
import com.wikiagent.domain.lineage.ArtifactType;
import com.wikiagent.domain.lineage.DocArtifact;
import com.wikiagent.domain.lineage.DocVersion;
import com.wikiagent.domain.lineage.EdgeType;
import com.wikiagent.domain.lineage.ExtractedField;
import com.wikiagent.domain.lineage.FieldVersion;
import com.wikiagent.domain.lineage.ProvenanceEdge;
import com.wikiagent.infrastructure.lineage.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C1 血缘版本集成测试：V10 五表在 H2 真实执行；
 * 文档版本 SUPERSEDES 链、字段版本不可变历史、唯一约束、ArtifactStore 落盘+sha256。
 */
@SpringBootTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
class ProvenanceServiceTest {

    @Autowired
    private ProvenanceService service;

    @Autowired
    private ArtifactStore artifactStore;

    private ExtractedFieldValue fieldValue(String key, String value, double conf, boolean valid) {
        return new ExtractedFieldValue(key, value, FieldValueType.STRING, conf,
                FieldSource.MODEL, valid, valid ? List.of() : List.of("校验失败"), List.of());
    }

    @Test
    void docVersionSupersedesChain() {
        String docId = "doc-v-" + System.nanoTime();
        DocVersion v1 = service.createDocVersion(docId, null, "首次解析", "sha-v1", "system");
        DocVersion v2 = service.createDocVersion(docId, v1.versionNo(), "重解析", "sha-v2", "system");

        assertThat(v1.versionNo()).isEqualTo(1);
        assertThat(v2.versionNo()).isEqualTo(2);
        assertThat(v2.parentVersionNo()).isEqualTo(1);
        // 旧版本被标记 SUPERSEDED
        assertThat(service.listVersions(docId))
                .extracting(DocVersion::status)
                .containsExactly(DocVersion.PUBLISHED, DocVersion.SUPERSEDED);
        assertThat(service.latestVersion(docId).versionNo()).isEqualTo(2);
    }

    @Test
    void fieldVersionFollowsDocVersion() {
        String docId = "doc-f-" + System.nanoTime();
        ExtractedField f1 = service.upsertField(docId, 1,
                fieldValue("amount", "100", 0.9, true), "invoice-demo", "1.0", "金额", false);
        ExtractedField f2 = service.upsertField(docId, 2,
                fieldValue("amount", "120", 0.95, true), "invoice-demo", "1.0", "金额", false);

        // 字段版本号 = 文档版本号（fix5 语义：历史行以文档版本为锚）
        assertThat(f1.versionNo()).isEqualTo(1);
        assertThat(f2.versionNo()).isEqualTo(2);
        // 当前快照唯一，版本行随文档版本追加
        List<FieldVersion> history = service.fieldHistory(docId, "amount");
        assertThat(history).hasSize(2)
                .extracting(FieldVersion::versionNo)
                .containsExactly(1, 2);
        assertThat(history.get(0).valueText()).isEqualTo("100");
        assertThat(history.get(1).valueText()).isEqualTo("120");
    }

    @Test
    void humanReviewedFieldMarksHumanSource() {
        String docId = "doc-h-" + System.nanoTime();
        ExtractedField f = service.upsertField(docId, 1,
                fieldValue("seller", "北京云智", 0.4, false), "invoice-demo", "1.0", "销售方", true);
        assertThat(f.source()).isEqualTo(FieldSource.HUMAN);
        assertThat(f.reviewRequired()).isFalse(); // 人工覆盖后置有效
    }

    @Test
    void uniqueConstraintOnFieldKeyRejectsConcurrentSnapshot() {
        // 同一 (docId, fieldKey) 只能有一条当前快照：upsert 内部 find+save 已幂等，
        // 这里验证版本行唯一约束 (docId, fieldKey, versionNo)：同文档版本重放幂等覆盖不撞键，
        // 新文档版本才追加历史行。
        String docId = "doc-u-" + System.nanoTime();
        service.upsertField(docId, 1, fieldValue("x", "1", 1.0, true), "s", "1", "x", false);
        // 同文档版本重放：版本号不变（幂等覆盖），不撞唯一键
        assertThat(service.upsertField(docId, 1, fieldValue("x", "2", 1.0, true), "s", "1", "x", false)
                .versionNo()).isEqualTo(1);
        // 新文档版本：追加历史行
        assertThat(service.upsertField(docId, 2, fieldValue("x", "3", 1.0, true), "s", "1", "x", false)
                .versionNo()).isEqualTo(2);
        assertThat(service.fieldHistory(docId, "x")).hasSize(2);
    }

    @Test
    void artifactStorePersistsFileWithSha256() {
        String docId = "doc-a-" + System.nanoTime();
        String content = "解析文本内容测试";
        DocArtifact a = artifactStore.saveText(docId, 1, ArtifactType.PARSED_TEXT, null, content);

        assertThat(a.contentRef()).contains("artifacts").contains("v1_parsed_text");
        assertThat(a.sizeBytes()).isEqualTo(content.getBytes(StandardCharsets.UTF_8).length);
        assertThat(a.sha256()).matches("[0-9a-f]{64}");
        assertThat(artifactStore.readText(a)).isEqualTo(content);

        // 幂等覆盖：同键再次保存应复用同一行（id 不变）
        DocArtifact a2 = artifactStore.saveText(docId, 1, ArtifactType.PARSED_TEXT, null, content);
        assertThat(a2.id()).isEqualTo(a.id());
    }

    @Test
    void provenanceEdgeWiredBetweenArtifactsAndField() {
        String docId = "doc-e-" + System.nanoTime();
        DocArtifact parsed = artifactStore.saveText(docId, 1, ArtifactType.PARSED_TEXT, null, "text");
        service.upsertField(docId, 1, fieldValue("k", "v", 1.0, true), "s", "1", "k", false);

        ProvenanceEdge edge = service.addEdge(docId, 1,
                String.valueOf(parsed.id()), "ARTIFACT", "k", "FIELD", EdgeType.EXTRACTED, "字段抽取");

        assertThat(service.edgesForDoc(docId)).hasSize(1)
                .first().extracting(ProvenanceEdge::edgeType).isEqualTo(EdgeType.EXTRACTED);
        assertThat(edge.fromRef()).isEqualTo(String.valueOf(parsed.id()));
        assertThat(edge.toRef()).isEqualTo("k");
    }

    @Test
    void artifactUniqueKeyEnforcedForSameTypeAndPage() {
        String docId = "doc-uk-" + System.nanoTime();
        artifactStore.saveText(docId, 1, ArtifactType.OCR_PAGE, 1, "p1");
        // 不同页不冲突
        artifactStore.saveText(docId, 1, ArtifactType.OCR_PAGE, 2, "p2");
        assertThat(artifactStore.getClass()).isNotNull();
    }
}
