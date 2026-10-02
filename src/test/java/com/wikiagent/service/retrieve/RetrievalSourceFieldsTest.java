package com.wikiagent.service.retrieve;

import com.wikiagent.application.llm.ModelCallRecorder;
import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.domain.llm.spi.RerankProvider;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * §2.5 引用六字段测试：
 * Milvus 命中 → Source 带代表子块 versionNo/pageNo/snippet/artifactId/filename；
 * 代表子块按"得分最高、平局 childIndex 最小"选出；本地降级路径字段同样透传。
 */
class RetrievalSourceFieldsTest {

    private static KbParentChunk parent() {
        KbParentChunk p = new KbParentChunk();
        p.setId("p1");
        p.setDocId("d1");
        p.setContent("父块内容");
        return p;
    }

    private static KbDocument doc() {
        KbDocument d = new KbDocument();
        d.setId("d1");
        d.setFilename("手册.md");
        return d;
    }

    private static KbChildChunk child(String id, int index, int version, Integer page, String content) {
        KbChildChunk c = new KbChildChunk();
        c.setId(id);
        c.setDocId("d1");
        c.setParentId("p1");
        c.setChildIndex(index);
        c.setVersionNo(version);
        c.setPageNo(page);
        c.setContent(content);
        return c;
    }

    private RetrievalService service(MilvusStoreService milvus, KbChildChunkRepo childRepo,
                                     KbParentChunkRepo parentRepo, KbDocumentRepo docRepo) {
        return service(milvus, childRepo, parentRepo, docRepo, provider(null));
    }

    private RetrievalService service(MilvusStoreService milvus, KbChildChunkRepo childRepo,
                                     KbParentChunkRepo parentRepo, KbDocumentRepo docRepo,
                                     ObjectProvider<KnowledgeMetadataJpaDao> metadataDao) {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(any(String.class))).thenReturn(new float[]{0.1f});
        WikiAgentProperties props = new WikiAgentProperties(null,
                new WikiAgentProperties.Retrieve(20, 10, 60, 12000), null, null);
        return new RetrievalService(props, milvus, embeddingModel, parentRepo, docRepo, childRepo,
                mock(MetricEventJpaDao.class),
                provider(null), metadataDao, false, provider(null), null);
    }

    private static <T> ObjectProvider<T> provider(T bean) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> op = mock(ObjectProvider.class);
        when(op.getIfAvailable()).thenReturn(bean);
        return op;
    }

    @Test
    void milvus命中时Source带代表子块六字段() {
        MilvusStoreService.Hit low = new MilvusStoreService.Hit("c1", 0.3, "d1", "p1", 0, 1, 0);
        MilvusStoreService.Hit high = new MilvusStoreService.Hit("c2", 0.9, "d1", "p1", 1, 1, 0);
        MilvusStoreService milvus = mock(MilvusStoreService.class);
        when(milvus.hybridSearch(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(List.of(low, high));

        KbChildChunkRepo childRepo = mock(KbChildChunkRepo.class);
        // 两个子块都能回查到，验证选出的是高分 c2
        when(childRepo.findByIdIn(any())).thenReturn(List.of(
                child("c1", 0, 1, 2, "低分子块"),
                child("c2", 1, 3, 7, "  高分代表子块内容  ")));
        KbParentChunkRepo parentRepo = mock(KbParentChunkRepo.class);
        when(parentRepo.findAllById(any())).thenReturn(List.of(parent()));
        KbDocumentRepo docRepo = mock(KbDocumentRepo.class);
        when(docRepo.findAllById(any())).thenReturn(List.of(doc()));
        // V11 knowledge_metadata 为代表子块 c2 回填 artifactId=77
        KnowledgeMetadataJpaDao metadataDao = mock(KnowledgeMetadataJpaDao.class);
        KnowledgeMetadataEntity meta = new KnowledgeMetadataEntity();
        meta.setChunkId("c2");
        meta.setArtifactId(77L);
        when(metadataDao.findByChunkIdIn(any())).thenReturn(List.of(meta));

        RetrievalService svc = service(milvus, childRepo, parentRepo, docRepo, provider(metadataDao));
        RetrievalService.RetrievalResult result = svc.assemble(
                svc.search(svc.newAccumulator(), List.of("查询")), "查询");

        assertEquals(1, result.sources().size());
        RetrievalService.Source s = result.sources().get(0);
        assertEquals(1, s.index());
        assertEquals("d1", s.docId());
        assertEquals(3, s.versionNo());       // 代表子块 c2 的版本
        assertEquals(7, s.pageNo());          // 代表子块 c2 的页码
        assertEquals("高分代表子块内容", s.snippet()); // strip 空白
        assertEquals("77", s.artifactId());   // V11 metadata.artifact_id 回填
        assertEquals(0.9, s.score(), 1e-9);
        assertEquals("手册.md", s.filename());
    }

    @Test
    void 同分子块平局时选childIndex小者() {
        MilvusStoreService.Hit a = new MilvusStoreService.Hit("c1", 0.5, "d1", "p1", 5, 1, 0);
        MilvusStoreService.Hit b = new MilvusStoreService.Hit("c2", 0.5, "d1", "p1", 2, 1, 0);
        MilvusStoreService milvus = mock(MilvusStoreService.class);
        when(milvus.hybridSearch(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(List.of(a, b));

        KbChildChunkRepo childRepo = mock(KbChildChunkRepo.class);
        when(childRepo.findByIdIn(any())).thenReturn(List.of(
                child("c1", 5, 1, 11, "索引大"),
                child("c2", 2, 2, 22, "索引小")));
        KbParentChunkRepo parentRepo = mock(KbParentChunkRepo.class);
        when(parentRepo.findAllById(any())).thenReturn(List.of(parent()));
        KbDocumentRepo docRepo = mock(KbDocumentRepo.class);
        when(docRepo.findAllById(any())).thenReturn(List.of(doc()));

        RetrievalService svc = service(milvus, childRepo, parentRepo, docRepo);
        RetrievalService.RetrievalResult result = svc.assemble(
                svc.search(svc.newAccumulator(), List.of("查询")), "查询");

        RetrievalService.Source s = result.sources().get(0);
        assertEquals(2, s.versionNo());
        assertEquals(22, s.pageNo());
        assertEquals("索引小", s.snippet());
    }

    @Test
    void 代表子块行缺失时assemble回退默认值() {
        // search 双门控会剔除子块行缺失的命中；直接构造累积器验证 assemble 的防御性回退
        KbChildChunkRepo childRepo = mock(KbChildChunkRepo.class);
        when(childRepo.findByIdIn(any())).thenReturn(List.of());
        KbParentChunkRepo parentRepo = mock(KbParentChunkRepo.class);
        when(parentRepo.findAllById(any())).thenReturn(List.of(parent()));
        KbDocumentRepo docRepo = mock(KbDocumentRepo.class);
        when(docRepo.findAllById(any())).thenReturn(List.of(doc()));

        RetrievalService svc = service(mock(MilvusStoreService.class), childRepo, parentRepo, docRepo);
        RetrievalService.Accumulator acc = svc.newAccumulator();
        acc.put("p1", "d1", "c1", 0, 0.8);
        RetrievalService.Source s = svc.assemble(acc, "查询").sources().get(0);
        assertEquals(0, s.versionNo());
        assertNull(s.pageNo());
        assertNull(s.snippet());
        assertNull(s.artifactId());
    }

    @Test
    void snippet去空白后超200字符截断() {
        String longContent = "  " + "x".repeat(260) + "  ";
        MilvusStoreService.Hit h = new MilvusStoreService.Hit("c1", 0.8, "d1", "p1", 0, 1, 0);
        MilvusStoreService milvus = mock(MilvusStoreService.class);
        when(milvus.hybridSearch(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(List.of(h));

        KbChildChunkRepo childRepo = mock(KbChildChunkRepo.class);
        when(childRepo.findByIdIn(any())).thenReturn(List.of(child("c1", 0, 1, 1, longContent)));
        KbParentChunkRepo parentRepo = mock(KbParentChunkRepo.class);
        when(parentRepo.findAllById(any())).thenReturn(List.of(parent()));
        KbDocumentRepo docRepo = mock(KbDocumentRepo.class);
        when(docRepo.findAllById(any())).thenReturn(List.of(doc()));

        RetrievalService svc = service(milvus, childRepo, parentRepo, docRepo);
        RetrievalService.Source s = svc.assemble(
                svc.search(svc.newAccumulator(), List.of("查询")), "查询").sources().get(0);
        assertEquals(200, s.snippet().length());
    }

    @Test
    void 本地关键词降级路径同样透传代表子块字段() {
        MilvusStoreService milvus = mock(MilvusStoreService.class);
        when(milvus.hybridSearch(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenThrow(new RuntimeException("milvus down"));

        KbChildChunkRepo childRepo = mock(KbChildChunkRepo.class);
        when(childRepo.findByContentContainingIgnoreCaseAndActiveTrue(
                org.mockito.ArgumentMatchers.contains("查询"), any()))
                .thenReturn(List.of(child("c9", 0, 5, 9, "本地命中片段")));
        when(childRepo.findByIdIn(any())).thenReturn(List.of(child("c9", 0, 5, 9, "本地命中片段")));
        KbParentChunkRepo parentRepo = mock(KbParentChunkRepo.class);
        when(parentRepo.findAllById(any())).thenReturn(List.of(parent()));
        KbDocumentRepo docRepo = mock(KbDocumentRepo.class);
        when(docRepo.findAllById(any())).thenReturn(List.of(doc()));

        RetrievalService svc = service(milvus, childRepo, parentRepo, docRepo);
        RetrievalService.Source s = svc.assemble(
                svc.search(svc.newAccumulator(), List.of("查询")), "查询").sources().get(0);
        assertEquals(5, s.versionNo());
        assertEquals(9, s.pageNo());
        assertEquals("本地命中片段", s.snippet());
        assertEquals("手册.md", s.filename());
        assertTrue(s.score() > 0);
    }
}
