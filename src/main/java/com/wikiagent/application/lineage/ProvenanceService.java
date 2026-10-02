package com.wikiagent.application.lineage;

import com.wikiagent.domain.extract.ExtractedFieldValue;
import com.wikiagent.domain.lineage.DocArtifact;
import com.wikiagent.domain.lineage.DocVersion;
import com.wikiagent.domain.lineage.EdgeType;
import com.wikiagent.domain.lineage.ExtractedField;
import com.wikiagent.domain.lineage.FieldVersion;
import com.wikiagent.domain.lineage.ProvenanceEdge;
import com.wikiagent.entity.lineage.DocVersionEntity;
import com.wikiagent.entity.lineage.ExtractedFieldEntity;
import com.wikiagent.entity.lineage.FieldVersionEntity;
import com.wikiagent.entity.lineage.ProvenanceEdgeEntity;
import com.wikiagent.repo.lineage.DocVersionRepo;
import com.wikiagent.repo.lineage.ExtractedFieldRepo;
import com.wikiagent.repo.lineage.FieldVersionRepo;
import com.wikiagent.repo.lineage.ProvenanceEdgeRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 血缘与版本编排（C1.3）：写 edge、版本递增、SUPERSEDES 链。
 * 产物落盘委托 {@link com.wikiagent.infrastructure.lineage.ArtifactStore}，本服务负责
 * 元数据持久化与字段/文档版本化（不可变历史 + 当前快照）。
 */
@Service
public class ProvenanceService {

    private final DocVersionRepo docVersionRepo;
    private final ExtractedFieldRepo fieldRepo;
    private final FieldVersionRepo fieldVersionRepo;
    private final ProvenanceEdgeRepo edgeRepo;

    public ProvenanceService(DocVersionRepo docVersionRepo, ExtractedFieldRepo fieldRepo,
                             FieldVersionRepo fieldVersionRepo, ProvenanceEdgeRepo edgeRepo) {
        this.docVersionRepo = docVersionRepo;
        this.fieldRepo = fieldRepo;
        this.fieldVersionRepo = fieldVersionRepo;
        this.edgeRepo = edgeRepo;
    }

    /** 创建文档新版本：parentVersionNo 为上一版本（SUPERSEDES 链）。 */
    @Transactional
    public DocVersion createDocVersion(String docId, Integer parentVersionNo, String changeSummary,
                                       String artifactSha256, String createdBy) {
        int nextNo = parentVersionNo == null ? 1 : parentVersionNo + 1;
        if (parentVersionNo != null) {
            docVersionRepo.findByDocIdAndVersionNo(docId, parentVersionNo).ifPresent(prev -> {
                prev.setStatus(DocVersion.SUPERSEDED);
                docVersionRepo.save(prev);
            });
        }
        DocVersionEntity e = new DocVersionEntity();
        e.setDocId(docId);
        e.setVersionNo(nextNo);
        e.setStatus(DocVersion.PUBLISHED);
        e.setParentVersionNo(parentVersionNo);
        e.setChangeSummary(changeSummary);
        e.setArtifactSha256(artifactSha256);
        e.setCreatedBy(createdBy);
        e.setCreatedAt(Instant.now());
        return toDocVersion(docVersionRepo.save(e));
    }

    public DocVersion latestVersion(String docId) {
        return docVersionRepo.findFirstByDocIdOrderByVersionNoDesc(docId)
                .map(ProvenanceService::toDocVersion).orElse(null);
    }

    public List<DocVersion> listVersions(String docId) {
        return docVersionRepo.findByDocIdOrderByVersionNoDesc(docId).stream()
                .map(ProvenanceService::toDocVersion).toList();
    }

    /**
     * 落抽取字段：更新当前快照（唯一键 docId+fieldKey），并写字段版本行。
     * 字段版本号 = 文档版本号（同文档版本重放幂等覆盖该行；重解析新版本追加历史行）。
     * reviewed=true 表示人工复核覆盖（source=HUMAN）。
     */
    @Transactional
    public ExtractedField upsertField(String docId, int docVersionNo, ExtractedFieldValue value,
                                      String schemaKey, String schemaVersion, String fieldLabel,
                                      boolean reviewed) {
        Integer pageNo = value.evidence().stream().map(com.wikiagent.domain.extract.FieldEvidence::pageNo)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        String snippet = value.evidence().stream().map(com.wikiagent.domain.extract.FieldEvidence::snippet)
                .filter(s -> s != null && !s.isBlank()).findFirst().orElse(null);
        return upsertField(docId, docVersionNo, value, schemaKey, schemaVersion, fieldLabel,
                reviewed, pageNo, snippet);
    }

    @Transactional
    public ExtractedField upsertField(String docId, int docVersionNo, ExtractedFieldValue value,
                                      String schemaKey, String schemaVersion, String fieldLabel,
                                      boolean reviewed, Integer pageNo, String snippet) {
        ExtractedFieldEntity cur = fieldRepo.findByDocIdAndFieldKey(docId, value.key()).orElse(null);
        Instant now = Instant.now();
        if (cur == null) {
            cur = new ExtractedFieldEntity();
            cur.setDocId(docId);
            cur.setFieldKey(value.key());
            cur.setCreatedAt(now);
        }
        cur.setFieldLabel(fieldLabel);
        cur.setValueText(value.value());
        cur.setValueType(value.valueType() == null ? null : value.valueType().name());
        cur.setConfidence(value.confidence());
        cur.setSource(reviewed ? com.wikiagent.domain.extract.FieldSource.HUMAN.name()
                : value.source().name());
        cur.setSchemaKey(schemaKey);
        cur.setSchemaVersion(schemaVersion);
        cur.setValid(reviewed || value.valid());
        cur.setReviewRequired(!reviewed && !value.valid());
        cur.setVersionNo(docVersionNo);
        cur.setPageNo(pageNo);
        cur.setSnippet(snippet);
        cur.setUpdatedAt(now);
        ExtractedFieldEntity saved = fieldRepo.save(cur);

        // 版本行按文档版本号幂等：同版本重放覆盖，不新增历史
        FieldVersionEntity fv = fieldVersionRepo
                .findByDocIdAndFieldKeyAndVersionNo(docId, value.key(), docVersionNo)
                .orElseGet(() -> {
                    FieldVersionEntity n = new FieldVersionEntity();
                    n.setDocId(docId);
                    n.setFieldKey(value.key());
                    n.setVersionNo(docVersionNo);
                    n.setCreatedAt(now);
                    return n;
                });
        fv.setValueText(value.value());
        fv.setConfidence(value.confidence());
        fv.setSource(reviewed ? com.wikiagent.domain.extract.FieldSource.HUMAN.name()
                : value.source().name());
        fv.setEditedBy(reviewed ? "human" : null);
        fv.setChangeReason(reviewed ? "人工复核覆盖" : "模型抽取");
        fieldVersionRepo.save(fv);

        return toField(saved);
    }

    /** 写血缘边（幂等：同 docId+version+from+to+type 已存在则跳过，断点重放不重复）。 */
    @Transactional
    public ProvenanceEdge addEdge(String docId, int versionNo, String fromRef, String fromType,
                                  String toRef, String toType, EdgeType edgeType, String note) {
        if (edgeRepo.existsByDocIdAndVersionNoAndFromRefAndToRefAndEdgeType(
                docId, versionNo, fromRef, toRef, edgeType.name())) {
            return edgeRepo.findByDocIdAndVersionNoOrderByIdAsc(docId, versionNo).stream()
                    .filter(e -> e.getFromRef().equals(fromRef) && e.getToRef().equals(toRef)
                            && e.getEdgeType().equals(edgeType.name()))
                    .findFirst().map(ProvenanceService::toEdge).orElse(null);
        }
        ProvenanceEdgeEntity e = new ProvenanceEdgeEntity();
        e.setDocId(docId);
        e.setVersionNo(versionNo);
        e.setFromRef(fromRef);
        e.setFromType(fromType);
        e.setToRef(toRef);
        e.setToType(toType);
        e.setEdgeType(edgeType.name());
        e.setNote(note);
        e.setCreatedAt(Instant.now());
        return toEdge(edgeRepo.save(e));
    }

    public List<ProvenanceEdge> edgesForDoc(String docId) {
        return edgeRepo.findByDocIdOrderByIdAsc(docId).stream()
                .map(ProvenanceService::toEdge).toList();
    }

    public List<FieldVersion> fieldHistory(String docId, String fieldKey) {
        return fieldVersionRepo.findByDocIdAndFieldKeyOrderByVersionNoAsc(docId, fieldKey)
                .stream().map(ProvenanceService::toFieldVersion).toList();
    }

    // ===== 映射 =====
    static DocVersion toDocVersion(DocVersionEntity e) {
        return new DocVersion(e.getId(), e.getDocId(), e.getVersionNo(), e.getStatus(),
                e.getParentVersionNo(), e.getChangeSummary(), e.getArtifactSha256(),
                e.getCreatedBy(), e.getCreatedAt());
    }

    public static ExtractedField toField(ExtractedFieldEntity e) {
        return new ExtractedField(e.getId(), e.getDocId(), e.getFieldKey(), e.getFieldLabel(),
                e.getValueText(),
                e.getValueType() == null ? null : com.wikiagent.domain.extract.FieldValueType.valueOf(e.getValueType()),
                e.getConfidence(),
                com.wikiagent.domain.extract.FieldSource.valueOf(e.getSource()),
                e.getSchemaKey(), e.getSchemaVersion(), e.isValid(), e.isReviewRequired(),
                e.getVersionNo(), e.getPageNo(), e.getSnippet(), e.getCreatedAt(), e.getUpdatedAt());
    }

    public static FieldVersion toFieldVersion(FieldVersionEntity e) {
        return new FieldVersion(e.getId(), e.getDocId(), e.getFieldKey(), e.getVersionNo(),
                e.getValueText(), e.getConfidence(),
                com.wikiagent.domain.extract.FieldSource.valueOf(e.getSource()),
                e.getEditedBy(), e.getChangeReason(), e.getCreatedAt());
    }

    static ProvenanceEdge toEdge(ProvenanceEdgeEntity e) {
        return new ProvenanceEdge(e.getId(), e.getDocId(), e.getVersionNo(),
                e.getFromRef(), e.getFromType(), e.getToRef(), e.getToType(),
                EdgeType.valueOf(e.getEdgeType()), e.getNote(), e.getCreatedAt());
    }

    public String artifactRef(DocArtifact a) {
        return String.valueOf(a.id());
    }
}
