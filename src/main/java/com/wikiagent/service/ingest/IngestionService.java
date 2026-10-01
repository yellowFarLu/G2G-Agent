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
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 入库流水线：解析 → 清洗 → 父/子切分 → 子块向量化 → 写 Milvus。
 * 状态机记录在 kb_document.status，前端轮询展示进度。
 * <p>
 * 两种驱动方式：
 * <ul>
 *   <li>任务框架（task.enabled=true）：{@code IngestTaskHandler} 按步骤调用各 Step 方法，断点续跑；</li>
 *   <li>旧异步入口 {@code @Async ingest(...)}：顺序组合 {@link #runPipeline}，异常兜底置 FAILED（enabled=false 或兼容路径）。</li>
 * </ul>
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

    /** 入库结果摘要。 */
    public record IngestOutcome(int parentCount, int childCount) {
    }

    // ===== 同步阶段方法（任务 handler 逐步调用；runPipeline 顺序组合）=====

    /** 步骤 2 解析：置 PARSING，按扩展名解析为纯文本。不支持/损坏的异常由调用方分类。 */
    public String parseStep(String docId, String filename, byte[] bytes) {
        KbDocument doc = requireDoc(docId);
        doc.setStatus(KbDocument.PARSING);
        docRepo.save(doc);
        return parser.parse(filename, bytes);
    }

    /** 步骤 3 清洗：置 CLEANING。 */
    public String cleanStep(String docId, String raw) {
        KbDocument doc = requireDoc(docId);
        doc.setStatus(KbDocument.CLEANING);
        docRepo.save(doc);
        return cleaner.clean(raw);
    }

    /**
     * 步骤 4 父/子切分：置 CHUNKING；幂等（子块已存在则跳过，断点重跑行数不翻倍），
     * 首次执行先清后写；v4 §6.6.1 知识打标（失败不阻断入库主流程，仅告警）。
     */
    @Transactional
    public IngestOutcome splitStep(String docId, String cleaned, KnowledgeTagContext tagContext) {
        KbDocument doc = requireDoc(docId);
        // 幂等跳过：子块已存在即上次 SPLIT 已完整落库（先清后写为单事务，无半写状态），
        // 直接沿用既有计数且不降级状态，保证断点重跑行数不翻倍、EMBED 的 READY 短路有效。
        if (!childRepo.findByDocId(docId).isEmpty()) {
            return new IngestOutcome(doc.getParentCount(), doc.getChildCount());
        }
        doc.setStatus(KbDocument.CHUNKING);
        docRepo.save(doc);
        ChunkSplitter splitter = new ChunkSplitter(
                props.ingest().parentChars(), props.ingest().parentOverlap(),
                props.ingest().childChars(), props.ingest().childOverlap());
        List<String> parents = splitter.splitParents(cleaned);
        if (parents.isEmpty()) {
            throw new IllegalStateException("文档清洗切分后为空，请检查文档内容");
        }

        childRepo.deleteByDocId(docId);
        parentRepo.deleteByDocId(docId);
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
        docRepo.save(doc);

        try {
            taggingService.tagDocument(docId, childEntities, tagContext);
        } catch (Exception tagEx) {
            log.warn("知识元数据打标失败 docId={}: {}", docId, tagEx.getMessage());
        }
        return new IngestOutcome(parentEntities.size(), childEntities.size());
    }

    /**
     * 步骤 5 向量化 + 写索引（DashScope 单次批量上限 10）。
     * 幂等：doc 已 READY（重复调度）返回 false 跳过，不重复写 Milvus。
     */
    public boolean embedAndPersistStep(String docId) {
        KbDocument doc = requireDoc(docId);
        if (KbDocument.READY.equals(doc.getStatus())) {
            return false;
        }
        doc.setStatus(KbDocument.EMBEDDING);
        docRepo.save(doc);
        List<KbChildChunk> childEntities = childRepo.findByDocId(docId);
        int batch = Math.max(1, props.ingest().embeddingBatch());
        List<float[]> allVectors = new ArrayList<>(childEntities.size());
        for (int i = 0; i < childEntities.size(); i += batch) {
            List<String> texts = childEntities.subList(i, Math.min(i + batch, childEntities.size()))
                    .stream().map(KbChildChunk::getContent).toList();
            allVectors.addAll(embeddingModel.embed(texts));
        }

        doc.setStatus(KbDocument.INDEXING);
        docRepo.save(doc);
        milvus.insertChildren(childEntities, allVectors);
        return true;
    }

    /** 步骤 6 索引校验 + READY 终态。 */
    public void finalizeStep(String docId) {
        KbDocument doc = requireDoc(docId);
        List<KbChildChunk> children = childRepo.findByDocId(docId);
        if (children.isEmpty()) {
            throw new IllegalStateException("索引校验失败：无子块 docId=" + docId);
        }
        doc.setStatus(KbDocument.READY);
        docRepo.save(doc);
        log.info("文档入库完成: docId={} filename={} children={}", docId, doc.getFilename(), children.size());
    }

    /** 全流水线（同步、异常上抛由调用方分类）：任务 handler 与旧异步入口共用。 */
    public IngestOutcome runPipeline(String docId, String filename, byte[] bytes, KnowledgeTagContext tagContext) {
        String raw = parseStep(docId, filename, bytes);
        String cleaned = cleanStep(docId, raw);
        IngestOutcome outcome = splitStep(docId, cleaned, tagContext);
        embedAndPersistStep(docId);
        finalizeStep(docId);
        return outcome;
    }

    // ===== 旧异步入口（enabled=false / 兼容路径）=====

    /** 兼容入口：使用保守默认标签（v4 §6.6.1）。 */
    @Async("ingestExecutor")
    public void ingest(String docId, String filename, byte[] bytes) {
        ingest(docId, filename, bytes, KnowledgeTagContext.defaultFor(filename));
    }

    /**
     * 入库流水线（异步）：runPipeline + 异常兜底置 FAILED（维持既有行为）。
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
            runPipeline(docId, filename, bytes, tagContext);
        } catch (Exception e) {
            log.error("文档入库失败: {}", filename, e);
            doc.setStatus(KbDocument.FAILED);
            doc.setError(summarize(e));
            docRepo.save(doc);
        }
    }

    /** 删除文档：先删 Milvus（失败则整体失败保持一致），再删本地。 */
    @Transactional
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

    private KbDocument requireDoc(String docId) {
        return docRepo.findById(docId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: " + docId));
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
