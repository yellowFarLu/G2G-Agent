package com.wikiagent.service.ingest;

import com.wikiagent.application.knowledge.KnowledgeTagContext;
import com.wikiagent.application.knowledge.KnowledgeTaggingService;
import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.entity.KbChildChunk;
import com.wikiagent.entity.KbDocument;
import com.wikiagent.entity.KbParentChunk;
import com.wikiagent.repo.KbChildChunkRepo;
import com.wikiagent.repo.KbDocumentRepo;
import com.wikiagent.repo.KbParentChunkRepo;
import com.wikiagent.service.store.MilvusStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 入库流水线（异步）：解析 → 清洗 → 父/子切分 → 子块向量化 → 写 Milvus。
 * 状态机记录在 kb_document.status，前端轮询展示进度。
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final WikiAgentProperties props;
    private final DocumentParser parser;
    private final TextCleaner cleaner;
    private final KbDocumentRepo docRepo;
    private final KbParentChunkRepo parentRepo;
    private final KbChildChunkRepo childRepo;
    private final MilvusStoreService milvus;
    private final EmbeddingModel embeddingModel;
    private final KnowledgeTaggingService taggingService;

    public IngestionService(WikiAgentProperties props, DocumentParser parser, TextCleaner cleaner,
                            KbDocumentRepo docRepo, KbParentChunkRepo parentRepo, KbChildChunkRepo childRepo,
                            MilvusStoreService milvus, EmbeddingModel embeddingModel,
                            KnowledgeTaggingService taggingService) {
        this.props = props;
        this.parser = parser;
        this.cleaner = cleaner;
        this.docRepo = docRepo;
        this.parentRepo = parentRepo;
        this.childRepo = childRepo;
        this.milvus = milvus;
        this.embeddingModel = embeddingModel;
        this.taggingService = taggingService;
    }

    /** 兼容入口：使用保守默认标签（v4 §6.6.1）。 */
    @Async("ingestExecutor")
    public void ingest(String docId, String filename, byte[] bytes) {
        ingest(docId, filename, bytes, KnowledgeTagContext.defaultFor(filename));
    }

    /**
     * 入库流水线（异步）。
     *
     * @param tagContext v4 知识打标上下文（9×6 领域标签 + 创建者元数据）
     */
    @Async("ingestExecutor")
    public void ingest(String docId, String filename, byte[] bytes, KnowledgeTagContext tagContext) {
        KbDocument doc = docRepo.findById(docId).orElse(null);
        if (doc == null) {
            return;
        }
        try {
            // 1. 解析
            doc.setStatus(KbDocument.PARSING);
            docRepo.save(doc);
            String raw = parser.parse(filename, bytes);

            // 2. 清洗
            doc.setStatus(KbDocument.CLEANING);
            docRepo.save(doc);
            String cleaned = cleaner.clean(raw);

            // 3. 父/子切分
            doc.setStatus(KbDocument.CHUNKING);
            docRepo.save(doc);
            ChunkSplitter splitter = new ChunkSplitter(
                    props.ingest().parentChars(), props.ingest().parentOverlap(),
                    props.ingest().childChars(), props.ingest().childOverlap());
            List<String> parents = splitter.splitParents(cleaned);
            if (parents.isEmpty()) {
                throw new IllegalStateException("文档清洗切分后为空，请检查文档内容");
            }

            // 父块先落库（事实源）
            List<KbParentChunk> parentEntities = new ArrayList<>(parents.size());
            List<KbChildChunk> childEntities = new ArrayList<>();
            for (int p = 0; p < parents.size(); p++) {
                String parentId = UUID.randomUUID().toString();
                parentEntities.add(newParent(parentId, docId, p, parents.get(p)));
                List<String> children = splitter.splitChildren(parents.get(p));
                for (int c = 0; c < children.size(); c++) {
                    childEntities.add(newChild(UUID.randomUUID().toString(), docId, parentId, c, children.get(c)));
                }
            }
            parentRepo.saveAll(parentEntities);
            childRepo.saveAll(childEntities);
            doc.setParentCount(parentEntities.size());
            doc.setChildCount(childEntities.size());

            // v4 §6.6.1 知识打标（失败不阻断入库主流程，仅告警）
            try {
                taggingService.tagDocument(docId, childEntities, tagContext);
            } catch (Exception tagEx) {
                log.warn("知识元数据打标失败 docId={}: {}", docId, tagEx.getMessage());
            }

            // 4. 子块向量化（DashScope 单次批量上限 10）
            doc.setStatus(KbDocument.EMBEDDING);
            docRepo.save(doc);
            int batch = Math.max(1, props.ingest().embeddingBatch());
            List<float[]> allVectors = new ArrayList<>(childEntities.size());
            for (int i = 0; i < childEntities.size(); i += batch) {
                List<String> texts = childEntities.subList(i, Math.min(i + batch, childEntities.size()))
                        .stream().map(KbChildChunk::getContent).toList();
                allVectors.addAll(embeddingModel.embed(texts));
            }

            // 5. 写入 Milvus
            doc.setStatus(KbDocument.INDEXING);
            docRepo.save(doc);
            milvus.insertChildren(childEntities, allVectors);

            doc.setStatus(KbDocument.READY);
            docRepo.save(doc);
            log.info("文档入库完成: {} parents={} children={}", filename, parentEntities.size(), childEntities.size());
        } catch (Exception e) {
            log.error("文档入库失败: {}", filename, e);
            doc.setStatus(KbDocument.FAILED);
            doc.setError(summarize(e));
            docRepo.save(doc);
        }
    }

    /** 删除文档：先删 Milvus（失败则整体失败保持一致），再删本地。 */
    @org.springframework.transaction.annotation.Transactional
    public void delete(String docId) {
        milvus.deleteByDocId(docId);
        childRepo.deleteByDocId(docId);
        parentRepo.deleteByDocId(docId);
        docRepo.deleteById(docId);
        try {
            Path dir = Path.of("data", "uploads", docId);
            if (Files.exists(dir)) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                }
            }
        } catch (Exception e) {
            log.warn("上传文件清理失败: {}", e.getMessage());
        }
    }

    private static String summarize(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return msg.length() > 1500 ? msg.substring(0, 1500) : msg;
    }

    private KbParentChunk newParent(String id, String docId, int idx, String content) {
        KbParentChunk p = new KbParentChunk();
        p.setId(id);
        p.setDocId(docId);
        p.setParentIndex(idx);
        p.setContent(content);
        return p;
    }

    private KbChildChunk newChild(String id, String docId, String parentId, int idx, String content) {
        KbChildChunk c = new KbChildChunk();
        c.setId(id);
        c.setDocId(docId);
        c.setParentId(parentId);
        c.setChildIndex(idx);
        c.setContent(content);
        return c;
    }
}
