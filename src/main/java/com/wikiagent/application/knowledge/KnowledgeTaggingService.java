package com.wikiagent.application.knowledge;

import com.wikiagent.entity.KbChildChunk;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataEntity;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataJpaDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * v4 §6.6.1 知识元数据打标服务。
 * <p>
 * 入库流水线在子 chunk 落库后调用本服务，为每条子 chunk 写一行
 * {@code knowledge_metadata}：domain/subDomain 垂直隔离标签、最低访问身份、
 * 创建时间、创建者、创建者身份、源文件、版本号。
 * <p>
 * 幂等：chunkId 已存在元数据时跳过（支持重复入库 / 任务重跑）。
 */
@Service
public class KnowledgeTaggingService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeTaggingService.class);

    private final KnowledgeMetadataJpaDao metadataDao;

    public KnowledgeTaggingService(KnowledgeMetadataJpaDao metadataDao) {
        this.metadataDao = metadataDao;
    }

    /**
     * 为一批子 chunk 写入元数据。
     *
     * @return 实际写入的行数（跳过已存在）
     */
    @Transactional
    public int tagDocument(String docId, List<KbChildChunk> chunks, KnowledgeTagContext ctx) {
        if (chunks == null || chunks.isEmpty() || ctx == null) {
            return 0;
        }
        Instant now = Instant.now();
        List<KnowledgeMetadataEntity> rows = new ArrayList<>(chunks.size());
        int skipped = 0;
        for (KbChildChunk chunk : chunks) {
            if (metadataDao.findByChunkId(chunk.getId()).isPresent()) {
                skipped++;
                continue;
            }
            KnowledgeMetadataEntity e = new KnowledgeMetadataEntity();
            e.setChunkId(chunk.getId());
            e.setDocId(docId);
            e.setDomainTag(ctx.domainTag());
            e.setSubDomainTag(ctx.subDomainTag());
            e.setRequiredIdentity(ctx.requiredIdentity());
            e.setCreatedAt(now);
            e.setCreatedBy(ctx.createdBy());
            e.setCreatedIdentity(ctx.createdIdentity());
            e.setSourceFilename(ctx.sourceFilename());
            e.setVersion(1);
            e.setIsActive(true);
            rows.add(e);
        }
        if (!rows.isEmpty()) {
            metadataDao.saveAll(rows);
        }
        log.info("知识打标完成 docId={} domain={}/{} written={} skipped={}",
                docId, ctx.domainTag(), ctx.subDomainTag(), rows.size(), skipped);
        return rows.size();
    }
}
