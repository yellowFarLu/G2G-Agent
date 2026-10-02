package com.wikiagent.service.retrieve;

import com.wikiagent.application.llm.ModelCallRecorder;
import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.domain.llm.ModelCallLogPurpose;
import com.wikiagent.domain.llm.spi.RerankProvider;
import com.wikiagent.entity.KbParentChunk;
import com.wikiagent.infrastructure.llm.DashScopeRerankProvider;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataJpaDao;
import com.wikiagent.infrastructure.persistence.MetricEventJpaDao;
import com.wikiagent.repo.KbChildChunkRepo;
import com.wikiagent.repo.KbDocumentRepo;
import com.wikiagent.repo.KbParentChunkRepo;
import com.wikiagent.service.store.MilvusStoreService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * E3 rerank 接线 + RERANK 打点测试（DashScope RestClient 桩，不联网）：
 * 重排成功 → 顺序改变 + SUCCESS 日志行；HTTP 500 → 保持原序 + FAILED 日志行。
 */
class RetrievalRerankLogTest {

    private static final String URL =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    private static KbParentChunk parent(String id, String docId, String content) {
        KbParentChunk p = new KbParentChunk();
        p.setId(id);
        p.setDocId(docId);
        p.setContent(content);
        return p;
    }

    private RetrievalService service(RerankProvider provider, ModelCallRecorder recorder,
                                     KbParentChunkRepo parentRepo) {
        @SuppressWarnings("unchecked")
        ObjectProvider<RerankProvider> rerankOp = mock(ObjectProvider.class);
        when(rerankOp.getIfAvailable()).thenReturn(provider);
        @SuppressWarnings("unchecked")
        ObjectProvider<KnowledgeMetadataJpaDao> metaOp = mock(ObjectProvider.class);
        when(metaOp.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        ObjectProvider<ModelCallRecorder> recorderOp = mock(ObjectProvider.class);
        when(recorderOp.getIfAvailable()).thenReturn(recorder);
        WikiAgentProperties props = new WikiAgentProperties(null,
                new WikiAgentProperties.Retrieve(20, 10, 60, 12000), null, null);
        return new RetrievalService(props, mock(MilvusStoreService.class), mock(EmbeddingModel.class),
                parentRepo, mock(KbDocumentRepo.class), mock(KbChildChunkRepo.class),
                mock(MetricEventJpaDao.class), rerankOp, metaOp, false, recorderOp, null);
    }

    private KbParentChunkRepo twoParents() {
        KbParentChunkRepo parentRepo = mock(KbParentChunkRepo.class);
        when(parentRepo.findAllById(any())).thenReturn(List.of(
                parent("p1", "d1", "内容甲"), parent("p2", "d2", "内容乙")));
        return parentRepo;
    }

    @Test
    void 重排成功时顺序改变并写SUCCESS日志() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
                .andRespond(withSuccess("""
                        {"output":{"results":[
                          {"index":1,"relevance_score":0.9},
                          {"index":0,"relevance_score":0.1}
                        ]}}
                        """, MediaType.APPLICATION_JSON));

        DashScopeRerankProvider provider =
                new DashScopeRerankProvider("k", true, builder.baseUrl(URL).build(), "gte-rerank");
        ModelCallRecorder recorder = mock(ModelCallRecorder.class);
        RetrievalService svc = service(provider, recorder, twoParents());

        RetrievalService.Accumulator acc = svc.newAccumulator();
        acc.put("p1", "d1", 0.5);
        acc.put("p2", "d2", 0.5);

        RetrievalService.RetrievalResult result = svc.assemble(acc, "查询");
        assertEquals("d2", result.sources().get(0).docId());
        assertEquals("d1", result.sources().get(1).docId());
        verify(recorder).record(eq(ModelCallLogPurpose.RERANK), eq("dashscope"), eq("gte-rerank"),
                isNull(), isNull(), anyLong(), eq(true), isNull(), isNull(), isNull());
        server.verify();
    }

    @Test
    void http500时保持原序并写FAILED日志() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
                .andRespond(withServerError());

        DashScopeRerankProvider provider =
                new DashScopeRerankProvider("k", true, builder.baseUrl(URL).build(), "gte-rerank");
        ModelCallRecorder recorder = mock(ModelCallRecorder.class);
        RetrievalService svc = service(provider, recorder, twoParents());

        RetrievalService.Accumulator acc = svc.newAccumulator();
        acc.put("p1", "d1", 0.9);
        acc.put("p2", "d2", 0.5);

        RetrievalService.RetrievalResult result = svc.assemble(acc, "查询");
        assertEquals("d1", result.sources().get(0).docId());
        verify(recorder).record(eq(ModelCallLogPurpose.RERANK), eq("dashscope"), eq("gte-rerank"),
                isNull(), isNull(), anyLong(), eq(false), isNull(), isNull(), isNull());
        server.verify();
    }
}
