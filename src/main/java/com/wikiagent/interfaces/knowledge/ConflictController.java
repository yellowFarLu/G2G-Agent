package com.wikiagent.interfaces.knowledge;

import com.wikiagent.application.knowledge.ConflictDetectionService;
import com.wikiagent.application.knowledge.ConflictResolutionService;
import com.wikiagent.entity.KbChildChunk;
import com.wikiagent.infrastructure.persistence.ConflictResolutionEntity;
import com.wikiagent.infrastructure.persistence.ConflictResolutionJpaDao;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataEntity;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataJpaDao;
import com.wikiagent.repo.KbChildChunkRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * v4 §6.6.3 知识冲突审核 Controller（代码冲突解决风格页面后端）。
 * <p>
 * GET    /api/conflicts                 — 获取冲突列表（按状态过滤）
 * GET    /api/conflicts/{id}             — 获取冲突详情
 * POST   /api/conflicts/{id}/resolve     — 解决冲突（KEEP_A / KEEP_B / MERGE / DELETE_A / DELETE_B / KEEP_BOTH）
 * POST   /api/conflicts/{id}/ignore      — 忽略（误报）
 * POST   /api/conflicts/scan             — 手动触发冲突扫描
 * GET    /api/conflicts/resolutions      — 支持的决议枚举
 */
@RestController
@RequestMapping("/api/conflicts")
public class ConflictController {

    private static final Logger log = LoggerFactory.getLogger(ConflictController.class);

    private final ConflictResolutionJpaDao conflictDao;
    private final ConflictResolutionService resolutionService;
    private final ConflictDetectionService detectionService;
    private final KbChildChunkRepo childChunkRepo;
    private final KnowledgeMetadataJpaDao metadataDao;

    public ConflictController(ConflictResolutionJpaDao conflictDao,
                              ConflictResolutionService resolutionService,
                              ConflictDetectionService detectionService,
                              KbChildChunkRepo childChunkRepo,
                              KnowledgeMetadataJpaDao metadataDao) {
        this.conflictDao = conflictDao;
        this.resolutionService = resolutionService;
        this.detectionService = detectionService;
        this.childChunkRepo = childChunkRepo;
        this.metadataDao = metadataDao;
    }

    @GetMapping
    public ResponseEntity<List<ConflictResolutionEntity>> listConflicts(
            @RequestParam(defaultValue = "DETECTED") String status) {
        return ResponseEntity.ok(conflictDao.findByStatus(status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ConflictResolutionEntity> getConflict(@PathVariable Long id) {
        return ResponseEntity.of(conflictDao.findById(id));
    }

    @GetMapping("/resolutions")
    public ResponseEntity<Map<String, Object>> allowedResolutions() {
        return ResponseEntity.ok(resolutionService.allowedResolutions());
    }

    /**
     * 冲突详情 + 双方 chunk 原文与知识元数据（"代码冲突合并式" 双栏对比页面后端）。
     */
    @GetMapping("/{id}/diff")
    public ResponseEntity<Map<String, Object>> conflictDiff(@PathVariable Long id) {
        ConflictResolutionEntity conflict = conflictDao.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("冲突不存在: " + id));
        Map<String, KbChildChunk> chunkMap = new java.util.HashMap<>();
        childChunkRepo.findByIdIn(java.util.List.of(conflict.getChunkIdA(), conflict.getChunkIdB()))
                .forEach(c -> chunkMap.put(c.getId(), c));
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("conflict", conflict);
        result.put("chunkA", toDiffView(conflict.getChunkIdA(), chunkMap.get(conflict.getChunkIdA())));
        result.put("chunkB", toDiffView(conflict.getChunkIdB(), chunkMap.get(conflict.getChunkIdB())));
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> toDiffView(String chunkId, KbChildChunk chunk) {
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("chunkId", chunkId);
        view.put("content", chunk == null ? null : chunk.getContent());
        view.put("docId", chunk == null ? null : chunk.getDocId());
        metadataDao.findByChunkId(chunkId).ifPresent(meta -> {
            view.put("domainTag", meta.getDomainTag());
            view.put("subDomainTag", meta.getSubDomainTag());
            view.put("createdBy", meta.getCreatedBy());
            view.put("createdIdentity", meta.getCreatedIdentity());
            view.put("sourceFilename", meta.getSourceFilename());
            view.put("version", meta.getVersion());
        });
        return view;
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<Map<String, Object>> resolveConflict(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        ConflictResolutionEntity updated = resolutionService.resolve(
                id,
                (String) body.get("resolution"),
                (String) body.getOrDefault("resolvedBy", "anonymous"),
                (String) body.get("comment"));
        return ResponseEntity.ok(Map.<String, Object>of(
                "status", "resolved",
                "conflictId", id,
                "resolution", updated.getResolution()));
    }

    @PostMapping("/{id}/ignore")
    public ResponseEntity<Map<String, Object>> ignoreConflict(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        String by = body == null ? "anonymous" : (String) body.getOrDefault("resolvedBy", "anonymous");
        String comment = body == null ? null : (String) body.get("comment");
        resolutionService.ignore(id, by, comment);
        return ResponseEntity.ok(Map.<String, Object>of("status", "ignored", "conflictId", id));
    }

    @PostMapping("/scan")
    public ResponseEntity<Map<String, Object>> triggerScan() {
        log.info("手动触发冲突扫描");
        int detected = detectionService.scan();
        return ResponseEntity.ok(Map.of(
                "status", "scan_completed",
                "newConflicts", detected));
    }
}
