package com.wikiagent.service.retrieve;

import com.wikiagent.domain.llm.spi.RerankProvider;
import com.wikiagent.domain.llm.spi.RerankRequest;
import com.wikiagent.domain.llm.spi.RerankResult;
import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.entity.KbParentChunk;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * E2 RetrievalService rerank 接线测试：rerank 重排、不可用原序、失败不阻断。
 */
class RetrievalRerankTest {

    private RetrievalService service(RerankProvider provider, KbParentChunkRepo parentRepo) {
        ObjectProvider<RerankProvider> op = mock(ObjectProvider.class);
        when(op.getIfAvailable()).thenReturn(provider);
        WikiAgentProperties props = new WikiAgentProperties(null,
                new WikiAgentProperties.Retrieve(20, 10, 60, 12000), null, null);
        return new RetrievalService(props, mock(MilvusStoreService.class), mock(EmbeddingModel.class),
                parentRepo, mock(KbDocumentRepo.class), mock(KbChildChunkRepo.class),
                mock(MetricEventJpaDao.class), op);
    }

    private static KbParentChunk parent(String id, String docId, String content) {
        KbParentChunk p = new KbParentChunk();
        p.setId(id);
        p.setDocId(docId);
        p.setContent(content);
        return p;
    }

    @Test
    void rerank可用时按分数重排结果顺序() {
        KbParentChunkRepo parentRepo = mock(KbParentChunkRepo.class);
        when(parentRepo.findAllById(any())).thenReturn(List.of(
                parent("p1", "d1", "内容甲"), parent("p2", "d2", "内容乙")));
        RerankProvider rerank = new RerankProvider() {
            @Override public RerankResult rerank(RerankRequest r) {
                return new RerankResult(List.of(0.1, 0.9)); // p2 更相关
            }
            @Override public boolean available() { return true; }
            @Override public String name() { return "fake"; }
        };
        RetrievalService svc = service(rerank, parentRepo);

        RetrievalService.Accumulator acc = svc.newAccumulator();
        acc.put("p1", "d1", 0.5);
        acc.put("p2", "d2", 0.5);

        RetrievalService.RetrievalResult result = svc.assemble(acc, "查询");
        assertEquals("d2", result.sources().get(0).docId());
        assertEquals("d1", result.sources().get(1).docId());
    }

    @Test
    void rerank不可用时保持原序() {
        KbParentChunkRepo parentRepo = mock(KbParentChunkRepo.class);
        when(parentRepo.findAllById(any())).thenReturn(List.of(
                parent("p1", "d1", "内容甲"), parent("p2", "d2", "内容乙")));
        RetrievalService svc = service(null, parentRepo);

        RetrievalService.Accumulator acc = svc.newAccumulator();
        acc.put("p1", "d1", 0.9);
        acc.put("p2", "d2", 0.5);

        RetrievalService.RetrievalResult result = svc.assemble(acc, "查询");
        assertEquals("d1", result.sources().get(0).docId());
        assertEquals("d2", result.sources().get(1).docId());
    }

    @Test
    void rerank抛异常时保持原序不阻断() {
        KbParentChunkRepo parentRepo = mock(KbParentChunkRepo.class);
        when(parentRepo.findAllById(any())).thenReturn(List.of(
                parent("p1", "d1", "内容甲"), parent("p2", "d2", "内容乙")));
        RerankProvider rerank = new RerankProvider() {
            @Override public RerankResult rerank(RerankRequest r) { throw new RuntimeException("down"); }
            @Override public boolean available() { return true; }
            @Override public String name() { return "fake"; }
        };
        RetrievalService svc = service(rerank, parentRepo);

        RetrievalService.Accumulator acc = svc.newAccumulator();
        acc.put("p1", "d1", 0.9);
        acc.put("p2", "d2", 0.5);

        RetrievalService.RetrievalResult result = svc.assemble(acc, "查询");
        assertEquals("d1", result.sources().get(0).docId());
    }
}
