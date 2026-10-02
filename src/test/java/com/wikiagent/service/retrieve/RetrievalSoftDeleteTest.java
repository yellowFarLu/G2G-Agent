package com.wikiagent.service.retrieve;

import com.wikiagent.application.llm.ModelCallRecorder;
import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.domain.llm.spi.RerankProvider;
import com.wikiagent.domain.retrieve.RetrievalQuery;
import com.wikiagent.entity.KbChildChunk;
import com.wikiagent.entity.KbDocument;
import com.wikiagent.entity.KbParentChunk;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataEntity;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataJpaDao;
import com.wikiagent.infrastructure.persistence.MetricEventJpaDao;
import com.wikiagent.repo.KbChildChunkRepo;
import com.wikiagent.repo.KbDocumentRepo;
import com.wikiagent.repo.KbParentChunkRepo;
import com.wikiagent.service.store.MilvusStoreService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * E6 关系库权威软删双门控测试（无 ACL 表达式，验证 is_active 在 filter 为空时也生效）：
 * 冲突 DELETE_A 只翻 knowledge_metadata.is_active（chunkId 维度）→ Milvus 仍召回 A/B，
 * A 必须零命中；kb_child_chunk.active=false 的命中被剔除；本地降级路径行为一致。
 */
class RetrievalSoftDeleteTest {

    private static KbChildChunk child(String id, String parentId, String docId, boolean active) {
        KbChildChunk c = new KbChildChunk();
        c.setId(id);
        c.setParentId(parentId);
        c.setDocId(docId);
        c.setChildIndex(0);
        c.setContent("内容" + id);
        c.setActive(active);
        return c;
    }

    private static KbParentChunk parent(String id, String docId) {
        KbParentChunk p = new KbParentChunk();
        p.setId(id);
        p.setDocId(docId);
        p.setContent("父块内容" + id);
        return p;
    }

    private static KbDocument doc(String id) {
        KbDocument d = new KbDocument();
        d.setId(id);
        d.setFilename(id + ".md");
        return d;
    }

    private static KnowledgeMetadataEntity meta(String chunkId, String docId, boolean active) {
        KnowledgeMetadataEntity e = new KnowledgeMetadataEntity();
        e.setChunkId(chunkId);
        e.setDocId(docId);
        e.setIsActive(active);
        return e;
    }

    private static <T> ObjectProvider<T> provider(T bean) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> op = mock(ObjectProvider.class);
        when(op.getIfAvailable()).thenReturn(bean);
        return op;
    }

    private RetrievalService service(MilvusStoreService milvus, KbChildChunkRepo childRepo,
                                     KnowledgeMetadataJpaDao metadataDao,
                                     KbParentChunkRepo parentRepo, KbDocumentRepo docRepo) {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        WikiAgentProperties props = new WikiAgentProperties(null,
                new WikiAgentProperties.Retrieve(20, 10, 60, 12000), null, null);
        return new RetrievalService(props, milvus, embeddingModel, parentRepo, docRepo, childRepo,
                mock(MetricEventJpaDao.class),
                provider((RerankProvider) null), provider(metadataDao), false,
                provider((ModelCallRecorder) null));
    }

    @Test
    void milvus路径冲突下线A后A零命中B命中() {
        MilvusStoreService.Hit hitA = new MilvusStoreService.Hit("c1", 0.8, "d1", "p1", 0, 1, 0);
        MilvusStoreService.Hit hitB = new MilvusStoreService.Hit("c2", 0.7, "d2", "p2", 0, 1, 0);
        MilvusStoreService milvus = mock(MilvusStoreService.class);
        when(milvus.hybridSearch(any(), anyString(), anyInt(), anyInt(), anyInt(), any()))
                .thenReturn(List.of(hitA, hitB));

        KbChildChunkRepo childRepo = mock(KbChildChunkRepo.class);
        when(childRepo.findByIdIn(anyList())).thenReturn(List.of(
                child("c1", "p1", "d1", true), child("c2", "p2", "d2", true)));
        KnowledgeMetadataJpaDao metadataDao = mock(KnowledgeMetadataJpaDao.class);
        // 冲突 DELETE_A：仅元数据软翻 is_active=false，Milvus 行不物理删除
        when(metadataDao.findByChunkIdIn(anyList())).thenReturn(List.of(
                meta("c1", "d1", false), meta("c2", "d2", true)));
        KbParentChunkRepo parentRepo = mock(KbParentChunkRepo.class);
        when(parentRepo.findAllById(any())).thenReturn(List.of(parent("p1", "d1"), parent("p2", "d2")));
        KbDocumentRepo docRepo = mock(KbDocumentRepo.class);
        when(docRepo.findAllById(any())).thenReturn(List.of(doc("d1"), doc("d2")));

        RetrievalService.RetrievalResult result = service(milvus, childRepo, metadataDao, parentRepo, docRepo)
                .retrieve(RetrievalQuery.of("查询", 1));

        assertEquals(1, result.sources().size());
        assertEquals("d2", result.sources().get(0).docId());
    }

    @Test
    void milvus路径子块active为false的命中被剔除() {
        MilvusStoreService.Hit hit = new MilvusStoreService.Hit("c1", 0.8, "d1", "p1", 0, 1, 0);
        MilvusStoreService milvus = mock(MilvusStoreService.class);
        when(milvus.hybridSearch(any(), anyString(), anyInt(), anyInt(), anyInt(), any()))
                .thenReturn(List.of(hit));

        KbChildChunkRepo childRepo = mock(KbChildChunkRepo.class);
        when(childRepo.findByIdIn(anyList())).thenReturn(List.of(child("c1", "p1", "d1", false)));
        KnowledgeMetadataJpaDao metadataDao = mock(KnowledgeMetadataJpaDao.class);
        when(metadataDao.findByChunkIdIn(anyList())).thenReturn(List.of(meta("c1", "d1", true)));
        KbParentChunkRepo parentRepo = mock(KbParentChunkRepo.class);
        when(parentRepo.findAllById(any())).thenReturn(List.of(parent("p1", "d1")));
        KbDocumentRepo docRepo = mock(KbDocumentRepo.class);
        when(docRepo.findAllById(any())).thenReturn(List.of(doc("d1")));

        RetrievalService.RetrievalResult result = service(milvus, childRepo, metadataDao, parentRepo, docRepo)
                .retrieve(RetrievalQuery.of("查询", 1));

        assertTrue(result.sources().isEmpty());
        assertTrue(result.context().isEmpty());
    }

    @Test
    void milvus路径子块行不存在时命中被剔除() {
        MilvusStoreService.Hit hit = new MilvusStoreService.Hit("ghost", 0.8, "d1", "p1", 0, 1, 0);
        MilvusStoreService milvus = mock(MilvusStoreService.class);
        when(milvus.hybridSearch(any(), anyString(), anyInt(), anyInt(), anyInt(), any()))
                .thenReturn(List.of(hit));

        KbChildChunkRepo childRepo = mock(KbChildChunkRepo.class);
        when(childRepo.findByIdIn(anyList())).thenReturn(List.of()); // 关系库无此行
        KnowledgeMetadataJpaDao metadataDao = mock(KnowledgeMetadataJpaDao.class);
        when(metadataDao.findByChunkIdIn(anyList())).thenReturn(List.of()); // 未打标默认放行
        KbParentChunkRepo parentRepo = mock(KbParentChunkRepo.class);
        KbDocumentRepo docRepo = mock(KbDocumentRepo.class);

        RetrievalService.RetrievalResult result = service(milvus, childRepo, metadataDao, parentRepo, docRepo)
                .retrieve(RetrievalQuery.of("查询", 1));

        assertTrue(result.sources().isEmpty());
    }

    @Test
    void 本地路径冲突下线A后A零命中B命中() {
        MilvusStoreService milvus = mock(MilvusStoreService.class);
        when(milvus.hybridSearch(any(), anyString(), anyInt(), anyInt(), anyInt(), any()))
                .thenThrow(new RuntimeException("milvus down"));

        KbChildChunkRepo childRepo = mock(KbChildChunkRepo.class);
        when(childRepo.findByContentContainingIgnoreCaseAndActiveTrue(anyString(), any(Pageable.class)))
                .thenReturn(List.of(child("c1", "p1", "d1", true), child("c2", "p2", "d2", true)));
        KnowledgeMetadataJpaDao metadataDao = mock(KnowledgeMetadataJpaDao.class);
        when(metadataDao.findByChunkIdIn(anyList())).thenReturn(List.of(
                meta("c1", "d1", false), meta("c2", "d2", true)));
        KbParentChunkRepo parentRepo = mock(KbParentChunkRepo.class);
        when(parentRepo.findAllById(any())).thenReturn(List.of(parent("p1", "d1"), parent("p2", "d2")));
        KbDocumentRepo docRepo = mock(KbDocumentRepo.class);
        when(docRepo.findAllById(any())).thenReturn(List.of(doc("d1"), doc("d2")));

        RetrievalService.RetrievalResult result = service(milvus, childRepo, metadataDao, parentRepo, docRepo)
                .retrieve(RetrievalQuery.of("查询", 1));

        assertEquals(1, result.sources().size());
        assertEquals("d2", result.sources().get(0).docId());
    }
}
