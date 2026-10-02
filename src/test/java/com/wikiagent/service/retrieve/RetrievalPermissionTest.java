package com.wikiagent.service.retrieve;

import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.domain.llm.spi.RerankProvider;
import com.wikiagent.domain.retrieve.RetrievalQuery;
import com.wikiagent.domain.retrieve.RetrievalSecurityContext;
import com.wikiagent.entity.KbChildChunk;
import com.wikiagent.entity.KbParentChunk;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataEntity;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataJpaDao;
import com.wikiagent.infrastructure.persistence.MetricEventJpaDao;
import com.wikiagent.repo.KbChildChunkRepo;
import com.wikiagent.repo.KbDocumentRepo;
import com.wikiagent.repo.KbParentChunkRepo;
import com.wikiagent.service.store.MilvusStoreService;
import org.junit.jupiter.api.AfterEach;
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
 * E5 检索权限过滤测试：两个 domain 的 chunk（industry/admin 与 pms/product），
 * Milvus 不可用走本地降级 + knowledge_metadata 关系库权威过滤。
 * admin 身份只命中对应 domain；越权身份命中 0 条；无身份不过滤。
 */
class RetrievalPermissionTest {

    @AfterEach
    void clearIdentity() {
        RetrievalSecurityContext.clear();
    }

    private static KbChildChunk child(String id, String parentId, String docId, String content) {
        KbChildChunk c = new KbChildChunk();
        c.setId(id);
        c.setParentId(parentId);
        c.setDocId(docId);
        c.setContent(content);
        c.setActive(true);
        return c;
    }

    private static KbParentChunk parent(String id, String docId, String content) {
        KbParentChunk p = new KbParentChunk();
        p.setId(id);
        p.setDocId(docId);
        p.setContent(content);
        return p;
    }

    private static KnowledgeMetadataEntity meta(String chunkId, String docId, String domain, String identity) {
        KnowledgeMetadataEntity e = new KnowledgeMetadataEntity();
        e.setChunkId(chunkId);
        e.setDocId(docId);
        e.setDomainTag(domain);
        e.setSubDomainTag("faq");
        e.setRequiredIdentity(identity);
        return e;
    }

    /**
     * 两个 domain 的检索环境：c1=industry/admin，c2=pms/product。
     * Milvus 抛异常 → 本地关键词降级；metadataDao 提供权威打标。
     */
    private RetrievalService twoDomainService() {
        MilvusStoreService milvus = mock(MilvusStoreService.class);
        when(milvus.hybridSearch(any(), anyString(), anyInt(), anyInt(), anyInt(), any()))
                .thenThrow(new RuntimeException("milvus down"));

        KbChildChunk c1 = child("c1", "p1", "d1", "行业方案退货流程说明");
        KbChildChunk c2 = child("c2", "p2", "d2", "PMS系统退货流程指引");
        KbChildChunkRepo childRepo = mock(KbChildChunkRepo.class);
        when(childRepo.findByContentContainingIgnoreCaseAndActiveTrue(anyString(), any(Pageable.class)))
                .thenReturn(List.of(c1, c2));

        KnowledgeMetadataJpaDao metadataDao = mock(KnowledgeMetadataJpaDao.class);
        when(metadataDao.findByChunkIdIn(anyList())).thenReturn(List.of(
                meta("c1", "d1", "industry", "admin"),
                meta("c2", "d2", "pms", "product")));

        KbParentChunkRepo parentRepo = mock(KbParentChunkRepo.class);
        when(parentRepo.findAllById(any())).thenReturn(List.of(
                parent("p1", "d1", "行业方案退货流程说明"),
                parent("p2", "d2", "PMS系统退货流程指引")));

        ObjectProvider<RerankProvider> rerankOp = mock(ObjectProvider.class);
        when(rerankOp.getIfAvailable()).thenReturn(null);
        ObjectProvider<KnowledgeMetadataJpaDao> metaOp = mock(ObjectProvider.class);
        when(metaOp.getIfAvailable()).thenReturn(metadataDao);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        WikiAgentProperties props = new WikiAgentProperties(null,
                new WikiAgentProperties.Retrieve(20, 10, 60, 12000), null, null);
        return new RetrievalService(props, milvus, embeddingModel,
                parentRepo, mock(KbDocumentRepo.class), childRepo,
                mock(MetricEventJpaDao.class), rerankOp, metaOp, false);
    }

    @Test
    void admin身份只命中对应domain的chunk() {
        RetrievalSecurityContext.setIdentity("admin");
        RetrievalService.RetrievalResult result = twoDomainService()
                .retrieve(RetrievalQuery.of("退货流程", 1));
        assertEquals(1, result.sources().size());
        assertEquals("d1", result.sources().get(0).docId());
        assertTrue(result.context().contains("行业方案"));
    }

    @Test
    void 越权身份命中0条() {
        // c1 要求 admin、c2 要求 product；business 两者均不可见
        RetrievalSecurityContext.setIdentity("business");
        RetrievalService.RetrievalResult result = twoDomainService()
                .retrieve(RetrievalQuery.of("退货流程", 1));
        assertTrue(result.sources().isEmpty());
        assertTrue(result.context().isEmpty());
    }

    @Test
    void 无身份头不过滤命中两条() {
        RetrievalService.RetrievalResult result = twoDomainService()
                .retrieve(RetrievalQuery.of("退货流程", 1));
        assertEquals(2, result.sources().size());
    }

    @Test
    void 显式表达式与身份AND合并() {
        RetrievalSecurityContext.setIdentity("admin");
        // admin 本可见 d1(industry)，但显式要求 domain=pms → 交集为空
        RetrievalService.RetrievalResult result = twoDomainService()
                .retrieve(RetrievalQuery.of("退货流程", "domain='pms'"));
        assertTrue(result.sources().isEmpty());
    }
}
