package com.wikiagent.infrastructure.observability.circuit;

import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.entity.KbChildChunk;
import com.wikiagent.entity.KbDocument;
import com.wikiagent.entity.KbParentChunk;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataJpaDao;
import com.wikiagent.infrastructure.persistence.MetricEventJpaDao;
import com.wikiagent.repo.KbChildChunkRepo;
import com.wikiagent.repo.KbDocumentRepo;
import com.wikiagent.repo.KbParentChunkRepo;
import com.wikiagent.service.retrieve.RetrievalService;
import com.wikiagent.service.store.MilvusStoreService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AC-I4 ① Milvus 不可用：混合检索抛错后自动降级为本地中文 bigram 关键词检索，
 * 结果非空、不抛异常，且观测供给器反射读到 milvusDisabledUntil 冷却窗口 → OPEN。
 */
class MilvusFallbackDegradationTest {

    @Test
    void milvus故障时本地关键词降级有结果且熔断状态OPEN() {
        MilvusStoreService milvus = mock(MilvusStoreService.class);
        when(milvus.hybridSearch(any(), any(), anyInt(), anyInt(), anyInt(), any()))
                .thenThrow(new RuntimeException("Milvus 连接拒绝"));

        KbChildChunk chunk = new KbChildChunk();
        chunk.setId("c1");
        chunk.setDocId("d1");
        chunk.setParentId("p1");
        chunk.setChildIndex(0);
        chunk.setVersionNo(1);
        chunk.setContent("报销流程说明：先提交申请单");
        chunk.setActive(true);

        KbChildChunkRepo childRepo = mock(KbChildChunkRepo.class);
        when(childRepo.findByContentContainingIgnoreCaseAndActiveTrue(anyString(), any(Pageable.class)))
                .thenReturn(List.of(chunk));

        KbParentChunk parent = new KbParentChunk();
        parent.setId("p1");
        parent.setDocId("d1");
        parent.setContent("报销制度说明：员工报销流程为先提交申请单，再由主管审批。");
        KbParentChunkRepo parentRepo = mock(KbParentChunkRepo.class);
        when(parentRepo.findAllById(any())).thenReturn(List.of(parent));

        KbDocument doc = new KbDocument();
        doc.setId("d1");
        doc.setFilename("报销制度.pdf");
        KbDocumentRepo docRepo = mock(KbDocumentRepo.class);
        when(docRepo.findAllById(any())).thenReturn(List.of(doc));

        @SuppressWarnings("unchecked")
        ObjectProvider<com.wikiagent.domain.llm.spi.RerankProvider> rerankOp = mock(ObjectProvider.class);
        when(rerankOp.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        ObjectProvider<KnowledgeMetadataJpaDao> metaOp = mock(ObjectProvider.class);
        when(metaOp.getIfAvailable()).thenReturn(null);

        WikiAgentProperties props = new WikiAgentProperties(
                null, new WikiAgentProperties.Retrieve(20, 10, 60, 12000), null, null);

        RetrievalService service = new RetrievalService(
                props, milvus, mock(EmbeddingModel.class), parentRepo, docRepo, childRepo,
                mock(MetricEventJpaDao.class), rerankOp, metaOp, false);

        // 故障前：反射读到 CLOSED
        assertThat(MilvusCircuitStateSupplier.stateOf(service)).isEqualTo(
                com.wikiagent.domain.observability.circuit.CircuitState.CLOSED);

        RetrievalService.RetrievalResult result = service.retrieve("报销流程");

        // 降级有结果、不抛异常，来源与文件名齐全
        assertThat(result.sources()).isNotEmpty();
        assertThat(result.sources().get(0).docId()).isEqualTo("d1");
        assertThat(result.sources().get(0).filename()).isEqualTo("报销制度.pdf");
        assertThat(result.context()).contains("报销");

        // 冷却窗口内：观测供给器报 OPEN（反射只读 milvusDisabledUntil）
        assertThat(MilvusCircuitStateSupplier.stateOf(service)).isEqualTo(
                com.wikiagent.domain.observability.circuit.CircuitState.OPEN);
    }
}
