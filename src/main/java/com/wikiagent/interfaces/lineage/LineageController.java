package com.wikiagent.interfaces.lineage;

import com.wikiagent.application.lineage.ProvenanceService;
import com.wikiagent.domain.lineage.DocVersion;
import com.wikiagent.domain.lineage.ExtractedField;
import com.wikiagent.domain.lineage.FieldVersion;
import com.wikiagent.entity.lineage.ExtractedFieldEntity;
import com.wikiagent.infrastructure.lineage.ArtifactStore;
import com.wikiagent.repo.lineage.DocArtifactRepo;
import com.wikiagent.repo.lineage.ExtractedFieldRepo;
import com.wikiagent.repo.lineage.FieldVersionRepo;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 子项目 C3：文档血缘与版本 REST API。
 * <ul>
 *   <li>GET /api/documents/{docId}/lineage — 全量血缘（版本+产物+边+字段）</li>
 *   <li>GET /api/documents/{docId}/fields/{key}/lineage — 单字段完整链（当前值+历史+边）</li>
 *   <li>GET /api/documents/{docId}/versions — 文档版本列表</li>
 *   <li>GET /api/documents/{docId}/versions/{a}/diff/{b} — 字段级版本 diff</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/documents")
public class LineageController {

    private final ProvenanceService provenance;
    private final ExtractedFieldRepo fieldRepo;
    private final DocArtifactRepo artifactRepo;
    private final FieldVersionRepo fieldVersionRepo;

    public LineageController(ProvenanceService provenance, ExtractedFieldRepo fieldRepo,
                             DocArtifactRepo artifactRepo, FieldVersionRepo fieldVersionRepo) {
        this.provenance = provenance;
        this.fieldRepo = fieldRepo;
        this.artifactRepo = artifactRepo;
        this.fieldVersionRepo = fieldVersionRepo;
    }

    @GetMapping("/{docId}/lineage")
    public ResponseEntity<Map<String, Object>> lineage(@PathVariable String docId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("docId", docId);
        result.put("versions", provenance.listVersions(docId));
        result.put("artifacts", artifactRepo.findByDocIdOrderByVersionNoAscIdAsc(docId).stream()
                .map(ArtifactStore::toDomain).toList());
        result.put("edges", provenance.edgesForDoc(docId));
        result.put("fields", fieldRepo.findByDocIdOrderByFieldKeyAsc(docId).stream()
                .map(ProvenanceService::toField).toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{docId}/fields/{key}/lineage")
    public ResponseEntity<Map<String, Object>> fieldLineage(@PathVariable String docId,
                                                            @PathVariable String key) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("docId", docId);
        result.put("fieldKey", key);
        ExtractedFieldEntity cur = fieldRepo.findByDocIdAndFieldKey(docId, key).orElse(null);
        result.put("current", cur == null ? null : ProvenanceService.toField(cur));
        result.put("history", provenance.fieldHistory(docId, key));
        result.put("edges", provenance.edgesForDoc(docId).stream()
                .filter(e -> key.equals(e.fromRef()) || key.equals(e.toRef()))
                .toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{docId}/versions")
    public ResponseEntity<List<DocVersion>> versions(@PathVariable String docId) {
        return ResponseEntity.ok(provenance.listVersions(docId));
    }

    /**
     * 字段级版本 diff：以文档版本号为锚，精确取该版本字段行，对比所有出现过的字段，标记 changed。
     * 版本不存在返回 404。
     */
    @GetMapping("/{docId}/versions/{a}/diff/{b}")
    public ResponseEntity<Map<String, Object>> diff(@PathVariable String docId,
                                                    @PathVariable int a,
                                                    @PathVariable int b) {
        if (!versionExists(docId, a) || !versionExists(docId, b)) {
            return ResponseEntity.notFound().build();
        }
        Map<String, FieldVersion> mapA = fieldVersionRepo.findByDocIdAndVersionNo(docId, a).stream()
                .map(ProvenanceService::toFieldVersion)
                .collect(Collectors.toMap(FieldVersion::fieldKey, v -> v, (x, y) -> y));
        Map<String, FieldVersion> mapB = fieldVersionRepo.findByDocIdAndVersionNo(docId, b).stream()
                .map(ProvenanceService::toFieldVersion)
                .collect(Collectors.toMap(FieldVersion::fieldKey, v -> v, (x, y) -> y));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (String key : java.util.stream.Stream
                .concat(mapA.keySet().stream(), mapB.keySet().stream())
                .distinct().sorted().toList()) {
            FieldVersion va = mapA.get(key);
            FieldVersion vb = mapB.get(key);
            String valA = va == null ? null : va.valueText();
            String valB = vb == null ? null : vb.valueText();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("fieldKey", key);
            row.put("valueA", valA);
            row.put("valueB", valB);
            row.put("changed", !java.util.Objects.equals(valA, valB));
            row.put("confidenceA", va == null ? null : va.confidence());
            row.put("confidenceB", vb == null ? null : vb.confidence());
            row.put("sourceA", va == null ? null : va.source());
            row.put("sourceB", vb == null ? null : vb.source());
            row.put("editedByA", va == null ? null : va.editedBy());
            row.put("editedByB", vb == null ? null : vb.editedBy());
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("docId", docId);
        result.put("versionA", a);
        result.put("versionB", b);
        result.put("fields", rows);
        return ResponseEntity.ok(result);
    }

    private boolean versionExists(String docId, int versionNo) {
        return provenance.listVersions(docId).stream()
                .anyMatch(v -> v.versionNo() == versionNo);
    }
}
