package com.wikiagent.service.ingest;

import com.wikiagent.application.extract.FieldExtractionService;
import com.wikiagent.application.knowledge.KnowledgeTaggingService;
import com.wikiagent.application.lineage.ProvenanceService;
import com.wikiagent.application.parse.PageStructureService;
import com.wikiagent.application.parse.RichDocumentParser;
import com.wikiagent.application.ragcache.EmbeddingCacheService;
import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.entity.KbChildChunk;
import com.wikiagent.entity.KbDocument;
import com.wikiagent.infrastructure.lineage.ArtifactStore;
import com.wikiagent.repo.KbChildChunkRepo;
import com.wikiagent.repo.KbDocumentRepo;
import com.wikiagent.repo.KbParentChunkRepo;
import com.wikiagent.service.store.MilvusStoreService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺陷14：入库向量化接 embedding 精确缓存——同批子块第二次入库不再调用 embedding 模型，
 * 且写入 Milvus 的向量与首次一致。
 */
class IngestionEmbeddingCacheTest {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T bean) {
        ObjectProvider<T> op = mock(ObjectProvider.class);
        when(op.getIfAvailable()).thenReturn(bean);
        return op;
    }

    /** 仅实现 embedding 缓存用到的 ValueOperations 语义的内存 Redis 桩。 */
    private static StringRedisTemplate fakeRedis(Map<String, String> store) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> vops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(vops);
        when(vops.get(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0)));
        doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(vops).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        return redis;
    }

    @Test
    void 同批子块二次入库时embedding模型零调用() {
        Map<String, String> store = new HashMap<>();
        EmbeddingCacheService cache =
                new EmbeddingCacheService(providerOf(fakeRedis(store)), true, 60);

        KbDocumentRepo docRepo = mock(KbDocumentRepo.class);
        KbChildChunkRepo childRepo = mock(KbChildChunkRepo.class);
        MilvusStoreService milvus = mock(MilvusStoreService.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);

        KbDocument doc = new KbDocument();
        doc.setStatus(KbDocument.CHUNKING);
        when(docRepo.findById("d1")).thenReturn(Optional.of(doc));
        when(docRepo.save(any(KbDocument.class))).thenAnswer(inv -> inv.getArgument(0));
        KbChildChunk c1 = new KbChildChunk();
        c1.setContent("子块一");
        KbChildChunk c2 = new KbChildChunk();
        c2.setContent("子块二");
        when(childRepo.findByDocIdAndActiveTrue("d1")).thenReturn(List.of(c1, c2));
        when(embeddingModel.embed(anyList()))
                .thenReturn(List.of(new float[]{1f, 2f}, new float[]{3f, 4f}));

        WikiAgentProperties props = new WikiAgentProperties(null, null,
                new WikiAgentProperties.Ingest(800, 100, 350, 60, 10), null);
        IngestionService svc = new IngestionService(props,
                mock(DocumentParser.class), mock(TextCleaner.class),
                mock(RichDocumentParser.class), mock(PageStructureService.class),
                mock(FieldExtractionService.class), mock(ProvenanceService.class),
                mock(ArtifactStore.class), docRepo, mock(KbParentChunkRepo.class),
                childRepo, milvus, embeddingModel, mock(KnowledgeTaggingService.class),
                providerOf(cache), "text-embedding-v4");

        // 首次入库：缓存全未命中，模型批量调用一次
        org.junit.jupiter.api.Assertions.assertTrue(svc.embedAndPersistStep("d1"));
        verify(embeddingModel, times(1)).embed(anyList());
        verify(milvus, times(1)).insertChildren(anyList(), anyList());

        // 第二次入库（模拟重跑）：全部命中缓存，模型零调用，向量仍照常写 Milvus
        org.junit.jupiter.api.Assertions.assertTrue(svc.embedAndPersistStep("d1"));
        verify(embeddingModel, times(1)).embed(anyList());
        verify(milvus, times(2)).insertChildren(anyList(), anyList());
    }

    @Test
    void 缓存开关关闭时二次入库仍直调模型() {
        Map<String, String> store = new HashMap<>();
        EmbeddingCacheService cache =
                new EmbeddingCacheService(providerOf(fakeRedis(store)), false, 60);

        KbDocumentRepo docRepo = mock(KbDocumentRepo.class);
        KbChildChunkRepo childRepo = mock(KbChildChunkRepo.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);

        KbDocument doc = new KbDocument();
        doc.setStatus(KbDocument.CHUNKING);
        when(docRepo.findById("d1")).thenReturn(Optional.of(doc));
        when(docRepo.save(any(KbDocument.class))).thenAnswer(inv -> inv.getArgument(0));
        KbChildChunk c1 = new KbChildChunk();
        c1.setContent("子块一");
        when(childRepo.findByDocIdAndActiveTrue("d1")).thenReturn(List.of(c1));
        when(embeddingModel.embed(anyList()))
                .thenReturn(List.of(new float[]{1f}));

        WikiAgentProperties props = new WikiAgentProperties(null, null,
                new WikiAgentProperties.Ingest(800, 100, 350, 60, 10), null);
        IngestionService svc = new IngestionService(props,
                mock(DocumentParser.class), mock(TextCleaner.class),
                mock(RichDocumentParser.class), mock(PageStructureService.class),
                mock(FieldExtractionService.class), mock(ProvenanceService.class),
                mock(ArtifactStore.class), docRepo, mock(KbParentChunkRepo.class),
                childRepo, mock(MilvusStoreService.class), embeddingModel,
                mock(KnowledgeTaggingService.class), providerOf(cache), "text-embedding-v4");

        svc.embedAndPersistStep("d1");
        svc.embedAndPersistStep("d1");
        // 开关关闭：embedAll 内部 bypass，两次都直调模型（缓存行为本身另见 RagCacheTest）
        verify(embeddingModel, times(2)).embed(anyList());
        org.junit.jupiter.api.Assertions.assertTrue(store.isEmpty());
    }
}
