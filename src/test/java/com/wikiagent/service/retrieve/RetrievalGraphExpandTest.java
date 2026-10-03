package com.wikiagent.service.retrieve;

import com.wikiagent.application.graph.GraphRagService;
import com.wikiagent.application.gray.GrayReleaseService;
import com.wikiagent.application.llm.ModelCallRecorder;
import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.domain.llm.spi.RerankProvider;
import com.wikiagent.entity.KbChildChunk;
import com.wikiagent.entity.KbDocument;
import com.wikiagent.entity.KbParentChunk;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GraphRAG 图扩展探针测试：
 * 多词自然语言查询整句无法匹配实体名时，bigram/拉丁词探针必须仍能命中文档中存在的实体，
 * 并将关联 chunk 以 0.5 中等置信分注入候选；图服务异常不得影响向量检索主流程。
 */
class RetrievalGraphExpandTest {

    private static KbChildChunk child(String id, String parentId, String docId, boolean active) {
        KbChildChunk c = new KbChildChunk();
        c.setId(id);
        c.setParentId(parentId);
        c.setDocId(docId);
        c.setChildIndex(0);
        c.setContent("WMS 于 2023 年 9 月升级到 3.0，引入 GraphRAG 技术" + id);
        c.setActive(active);
        return c;
    }

    private static KbParentChunk parent(String id, String docId) {
        KbParentChunk p = new KbParentChunk();
        p.setId(id);
        p.setDocId(docId);
        p.setContent("父块" + id);
        return p;
    }

    private static KbDocument doc(String id) {
        KbDocument d = new KbDocument();
        d.setId(id);
        d.setFilename(id + ".txt");
        return d;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> op = mock(ObjectProvider.class);
        when(op.getIfAvailable()).thenReturn(bean);
        return op;
    }

    private RetrievalService service(GraphRagService graph, KbChildChunkRepo childRepo,
                                     KbParentChunkRepo parentRepo, KbDocumentRepo docRepo,
                                     MilvusStoreService milvus) {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        WikiAgentProperties props = new WikiAgentProperties(null,
                new WikiAgentProperties.Retrieve(20, 10, 60, 12000), null, null);
        return new RetrievalService(props, milvus, embeddingModel, parentRepo, docRepo, childRepo,
                mock(MetricEventJpaDao.class),
                provider((RerankProvider) null),
                provider(mock(KnowledgeMetadataJpaDao.class)), false,
                provider((ModelCallRecorder) null),
                provider((GrayReleaseService) null),
                provider(graph));
    }

    @Test
    void 多词查询经拉丁词探针命中实体并注入关联chunk() {
        String query = "WMS什么时候升级到3.0的？";
        MilvusStoreService milvus = mock(MilvusStoreService.class);
        when(milvus.hybridSearch(any(), anyString(), anyInt(), anyInt(), anyInt(), any()))
                .thenReturn(List.of()); // 向量路零命中，只能靠图扩展

        GraphRagService graph = mock(GraphRagService.class);
        // 整句探针与各 bigram 探针均无实体，仅拉丁词 "wms" 命中实体「仓储管理系统 WMS」
        when(graph.searchWithExpansion(anyString())).thenReturn(GraphRagService.GraphSearchResult.empty());
        when(graph.searchWithExpansion(eq("wms"))).thenReturn(new GraphRagService.GraphSearchResult(
                List.of(), List.of(), List.of(), Set.of("c-wms")));

        KbChildChunkRepo childRepo = mock(KbChildChunkRepo.class);
        when(childRepo.findByIdIn(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.of(child("c-wms", "p1", "d1", true)));
        KbParentChunkRepo parentRepo = mock(KbParentChunkRepo.class);
        when(parentRepo.findAllById(any())).thenReturn(List.of(parent("p1", "d1")));
        KbDocumentRepo docRepo = mock(KbDocumentRepo.class);
        when(docRepo.findAllById(any())).thenReturn(List.of(doc("d1")));

        var result = service(graph, childRepo, parentRepo, docRepo, milvus)
                .retrieve(List.of(query));

        assertEquals(1, result.sources().size());
        assertEquals("d1", result.sources().get(0).docId());
        // 图扩展固定 0.5 中等置信分
        assertEquals(0.5, result.sources().get(0).score(), 1e-9);
    }

    @Test
    void 图服务异常时向量检索主流程不受影响() {
        MilvusStoreService milvus = mock(MilvusStoreService.class);
        MilvusStoreService.Hit hit = new MilvusStoreService.Hit("c1", 0.8, "d1", "p1", 0, 1, 0);
        when(milvus.hybridSearch(any(), anyString(), anyInt(), anyInt(), anyInt(), any()))
                .thenReturn(List.of(hit));

        GraphRagService graph = mock(GraphRagService.class);
        when(graph.searchWithExpansion(anyString())).thenThrow(new RuntimeException("graph db error"));

        KbChildChunkRepo childRepo = mock(KbChildChunkRepo.class);
        when(childRepo.findByIdIn(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.of(child("c1", "p1", "d1", true)));
        KbParentChunkRepo parentRepo = mock(KbParentChunkRepo.class);
        when(parentRepo.findAllById(any())).thenReturn(List.of(parent("p1", "d1")));
        KbDocumentRepo docRepo = mock(KbDocumentRepo.class);
        when(docRepo.findAllById(any())).thenReturn(List.of(doc("d1")));

        var result = service(graph, childRepo, parentRepo, docRepo, milvus)
                .retrieve(List.of("随便问点什么"));

        assertEquals(1, result.sources().size());
        assertEquals("d1", result.sources().get(0).docId());
        // 向量原始分保留，未被图信号覆盖
        assertTrue(result.sources().get(0).score() > 0.5);
    }
}
