package com.wikiagent.application.knowledge;

import com.wikiagent.infrastructure.persistence.ConflictResolutionEntity;
import com.wikiagent.infrastructure.persistence.ConflictResolutionJpaDao;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataEntity;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataJpaDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * v4 §6.6.3 知识冲突解决服务（对应"代码冲突解决风格"的审核页面操作）。
 * <p>
 * 支持的处置决议（resolution）：
 * <ul>
 *   <li>{@code KEEP_A} / {@code KEEP_B}：保留一方（记录决议，不删除另一方，需人工确认后再 DELETE）</li>
 *   <li>{@code KEEP_BOTH}：判定为互补知识，并存</li>
 *   <li>{@code MERGE}：已人工合并（合并动作在编辑器中完成，本服务仅记录状态）</li>
 *   <li>{@code DELETE_A} / {@code DELETE_B}：淘汰一方——将对应 knowledge_metadata.is_active 置 false</li>
 * </ul>
 * <p>
 * <b>诚实声明</b>：DELETE 仅下线 MySQL 元数据（检索期按 is_active 过滤）；
 * Milvus 向量删除按 docId 粒度（{@code MilvusStoreService.deleteByDocId}），
 * chunk 级软删与 Milvus 硬删的联动留待 §6 检索过滤层实施，此处不虚构 chunk 级删除 API。
 */
@Service
public class ConflictResolutionService {

    private static final Logger log = LoggerFactory.getLogger(ConflictResolutionService.class);

    static final Set<String> ALLOWED_RESOLUTIONS = Set.of(
            "KEEP_A", "KEEP_B", "KEEP_BOTH", "MERGE", "DELETE_A", "DELETE_B");

    private final ConflictResolutionJpaDao conflictDao;
    private final KnowledgeMetadataJpaDao metadataDao;

    public ConflictResolutionService(ConflictResolutionJpaDao conflictDao,
                                     KnowledgeMetadataJpaDao metadataDao) {
        this.conflictDao = conflictDao;
        this.metadataDao = metadataDao;
    }

    /** 处置冲突。 */
    @Transactional
    public ConflictResolutionEntity resolve(Long conflictId, String resolution,
                                            String resolvedBy, String comment) {
        if (resolution == null || !ALLOWED_RESOLUTIONS.contains(resolution)) {
            throw new IllegalArgumentException(
                    "非法 resolution=" + resolution + "，允许值：" + ALLOWED_RESOLUTIONS);
        }
        ConflictResolutionEntity conflict = conflictDao.findById(conflictId)
                .orElseThrow(() -> new IllegalArgumentException("冲突不存在: " + conflictId));

        conflict.setStatus("RESOLVED");
        conflict.setResolution(resolution);
        conflict.setResolvedBy(resolvedBy);
        conflict.setResolutionComment(comment);
        conflict.setResolvedAt(Instant.now());
        conflictDao.save(conflict);

        // DELETE_A / DELETE_B：下线对应知识的元数据
        if ("DELETE_A".equals(resolution)) {
            deactivate(conflict.getChunkIdA(), conflictId, resolvedBy);
        } else if ("DELETE_B".equals(resolution)) {
            deactivate(conflict.getChunkIdB(), conflictId, resolvedBy);
        }
        log.info("冲突 {} 处置完成: {} by {}", conflictId, resolution, resolvedBy);
        return conflict;
    }

    /** 忽略（误报）冲突。 */
    @Transactional
    public ConflictResolutionEntity ignore(Long conflictId, String resolvedBy, String comment) {
        ConflictResolutionEntity conflict = conflictDao.findById(conflictId)
                .orElseThrow(() -> new IllegalArgumentException("冲突不存在: " + conflictId));
        conflict.setStatus("IGNORED");
        conflict.setResolvedBy(resolvedBy);
        conflict.setResolutionComment(comment);
        conflict.setResolvedAt(Instant.now());
        conflictDao.save(conflict);
        log.info("冲突 {} 被忽略 by {}", conflictId, resolvedBy);
        return conflict;
    }

    private void deactivate(String chunkId, Long conflictId, String resolvedBy) {
        metadataDao.findByChunkId(chunkId).ifPresentOrElse(meta -> {
            meta.setIsActive(false);
            metadataDao.save(meta);
            log.info("冲突 {} 处置：知识 {} 已下线 is_active=false（操作人 {}）",
                    conflictId, chunkId, resolvedBy);
        }, () -> log.warn("冲突 {} 处置：待下线 chunk {} 的元数据不存在，跳过", conflictId, chunkId));
    }

    /** 返回允许的决议集合（供前端渲染按钮）。 */
    public Map<String, Object> allowedResolutions() {
        return Map.of(
                "resolutions", ALLOWED_RESOLUTIONS,
                "statuses", Set.of("DETECTED", "RESOLVED", "IGNORED"));
    }
}
