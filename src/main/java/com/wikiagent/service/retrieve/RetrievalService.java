package com.wikiagent.service.retrieve;

import com.wikiagent.application.llm.ModelCallRecorder;
import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.domain.llm.ModelCallLogPurpose;
import com.wikiagent.domain.llm.spi.RerankProvider;
import com.wikiagent.domain.llm.spi.RerankRequest;
import com.wikiagent.domain.llm.spi.RerankResult;
import com.wikiagent.domain.retrieve.RetrievalFilter;
import com.wikiagent.domain.retrieve.RetrievalQuery;
import com.wikiagent.domain.retrieve.RetrievalSecurityContext;
import com.wikiagent.entity.KbChildChunk;
import com.wikiagent.entity.KbDocument;
import com.wikiagent.entity.KbParentChunk;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataEntity;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataJpaDao;
import com.wikiagent.infrastructure.persistence.MetricEventEntity;
import com.wikiagent.infrastructure.persistence.MetricEventJpaDao;
import com.wikiagent.repo.KbChildChunkRepo;
import com.wikiagent.repo.KbDocumentRepo;
import com.wikiagent.repo.KbParentChunkRepo;
import com.wikiagent.service.store.MilvusStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 混合检索：查询向量化 → Milvus 双路召回（BM25 + 向量，服务端 RRF 融合）
 * → top N 子块替换为父文档（按命中顺序去重、字符预算内组装上下文）。
 *
 * 通过 Accumulator 支持多轮检索累积：跨轮按父块去重（保留最高分与首次命中顺序），
 * 最终统一组装，供 Agentic RAG 迭代式检索使用。
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    /** Milvus 故障后的本地降级冷却时长（毫秒），冷却期内直接走 DB 关键词检索，避免每次 10s 超时。 */
    private static final long FALLBACK_COOLDOWN_MS = 60_000L;
    /** 本地降级：每个查询最多抽取的 bigram/关键词数量。 */
    private static final int FALLBACK_MAX_TERMS = 12;

    /**
     * 引用来源（§2.5 六字段 + filename）。
     * <ul>
     *   <li>{@code versionNo}/{@code pageNo}：取该父块代表子块（父块下最高分、平局取 childIndex 小者）
     *       的实体值；代表子块行缺失时 versionNo=0、pageNo=null</li>
     *   <li>{@code snippet}：代表子块 content 去空白后前 200 字符</li>
     *   <li>{@code artifactId}：预留字段（恒 null）。kb_child_chunk 表无该列，
     *       未来可由 knowledge_metadata.artifact_id 回填</li>
     *   <li>{@code filename}：保留在末尾，JSON 仍含 filename 以兼容现有前端</li>
     * </ul>
     * 序列化 JSON 字段名保持驼峰（index/docId/versionNo/pageNo/snippet/artifactId/score/filename）。
     */
    public record Source(int index, String docId, int versionNo, Integer pageNo, String snippet,
                         String artifactId, double score, String filename) {
    }

    /** context 为空表示知识库中没有检索到相关内容。 */
    public record RetrievalResult(List<Source> sources, String context) {
    }

    /** 多轮检索累积器：按父块 ID 去重的命中集合。 */
    public static final class Accumulator {
        private final LinkedHashSet<String> parentOrder = new LinkedHashSet<>();
        private final Map<String, Double> bestScore = new HashMap<>();
        private final Map<String, String> parentDoc = new HashMap<>();
        /** 每个父块的代表子块（§2.5：同父块得分最高；平局取 childIndex 小者）。 */
        private final Map<String, RepChild> repChild = new HashMap<>();

        /** 代表子块引用：childId + childIndex + 该子块自身命中分。 */
        record RepChild(String childId, int childIndex, double score) {
        }

        /** 测试辅助：直接放入一条命中（同包可见，无代表子块，引用六字段回退默认值）。 */
        void put(String parentId, String docId, double score) {
            parentOrder.add(parentId);
            bestScore.merge(parentId, score, Math::max);
            parentDoc.putIfAbsent(parentId, docId);
        }

        /** 累积一条带子块的命中，并维护父块代表子块。 */
        void put(String parentId, String docId, String childId, int childIndex, double score) {
            put(parentId, docId, score);
            noteChild(parentId, childId, childIndex, score);
        }

        /** 按"得分最高、平局 childIndex 最小"更新代表子块。 */
        void noteChild(String parentId, String childId, int childIndex, double score) {
            if (childId == null) {
                return;
            }
            RepChild cur = repChild.get(parentId);
            if (cur == null || score > cur.score() || (score == cur.score() && childIndex < cur.childIndex())) {
                repChild.put(parentId, new RepChild(childId, childIndex, score));
            }
        }
    }

    private final WikiAgentProperties props;
    private final MilvusStoreService milvus;
    private final EmbeddingModel embeddingModel;
    private final KbParentChunkRepo parentRepo;
    private final KbDocumentRepo docRepo;
    private final KbChildChunkRepo childRepo;
    private final MetricEventJpaDao metricEventDao;
    /** E2：可选 rerank provider（不可用时为 null，检索按原序返回）。 */
    private final RerankProvider rerankProvider;
    /** E5：知识元数据 DAO（可选，权限过滤用；缺失时不过滤）。 */
    private final KnowledgeMetadataJpaDao metadataDao;
    /** E5：是否把权限表达式下推 Milvus（存量集合缺标量列时须保持 false）。 */
    private final boolean milvusFilterMetadata;
    /** E3：RERANK 打点器（可选，缺失时只重排不打点）。 */
    private final ModelCallRecorder callRecorder;

    /** Milvus 不可用截止时间戳；0 表示正常，>now 表示冷却降级中。 */
    private volatile long milvusDisabledUntil = 0L;

    /** 兼容旧构造（测试/旧装配）：无 rerank provider、无权限过滤。 */
    public RetrievalService(WikiAgentProperties props, MilvusStoreService milvus, EmbeddingModel embeddingModel,
                            KbParentChunkRepo parentRepo, KbDocumentRepo docRepo, KbChildChunkRepo childRepo,
                            MetricEventJpaDao metricEventDao) {
        this(props, milvus, embeddingModel, parentRepo, docRepo, childRepo, metricEventDao, null, null, false);
    }

    /** E5 构造（兼容）：无 RERANK 打点器。 */
    public RetrievalService(WikiAgentProperties props, MilvusStoreService milvus, EmbeddingModel embeddingModel,
                            KbParentChunkRepo parentRepo, KbDocumentRepo docRepo, KbChildChunkRepo childRepo,
                            MetricEventJpaDao metricEventDao,
                            org.springframework.beans.factory.ObjectProvider<RerankProvider> rerankProvider,
                            org.springframework.beans.factory.ObjectProvider<KnowledgeMetadataJpaDao> metadataDao,
                            boolean milvusFilterMetadata) {
        this(props, milvus, embeddingModel, parentRepo, docRepo, childRepo, metricEventDao,
                rerankProvider, metadataDao, milvusFilterMetadata, null);
    }

    /**
     * E2/E3/E5 全量装配构造。
     *
     * @param callRecorder RERANK 打点器（ObjectProvider 可选；无 Bean 时不打点）
     */
    @org.springframework.beans.factory.annotation.Autowired
    public RetrievalService(WikiAgentProperties props, MilvusStoreService milvus, EmbeddingModel embeddingModel,
                            KbParentChunkRepo parentRepo, KbDocumentRepo docRepo, KbChildChunkRepo childRepo,
                            MetricEventJpaDao metricEventDao,
                            org.springframework.beans.factory.ObjectProvider<RerankProvider> rerankProvider,
                            org.springframework.beans.factory.ObjectProvider<KnowledgeMetadataJpaDao> metadataDao,
                            @org.springframework.beans.factory.annotation.Value(
                                    "${wikiagent.milvus.filter-metadata:false}") boolean milvusFilterMetadata,
                            org.springframework.beans.factory.ObjectProvider<ModelCallRecorder> callRecorder) {
        this.props = props;
        this.milvus = milvus;
        this.embeddingModel = embeddingModel;
        this.parentRepo = parentRepo;
        this.docRepo = docRepo;
        this.childRepo = childRepo;
        this.metricEventDao = metricEventDao;
        RerankProvider rp = rerankProvider == null ? null : rerankProvider.getIfAvailable();
        this.rerankProvider = rp != null && rp.available() ? rp : null;
        this.metadataDao = metadataDao == null ? null : metadataDao.getIfAvailable();
        this.milvusFilterMetadata = milvusFilterMetadata;
        this.callRecorder = callRecorder == null ? null : callRecorder.getIfAvailable();
    }

    /** 单次多查询检索（一次组装，rerank 可用时重排）。 */
    public RetrievalResult retrieve(List<String> queries) {
        Accumulator acc = search(new Accumulator(), queries);
        String rerankQuery = firstNonBlank(queries);
        RetrievalResult result = assemble(acc, rerankQuery);
        recordRetrievalMetrics(result);
        return result;
    }

    /** 单查询单次检索（兼容旧链路）。 */
    public RetrievalResult retrieve(String query) {
        return retrieve(List.of(query));
    }

    /**
     * E5：带权限表达式的单查询检索。filterExpression 与当前请求身份
     * （X-Business-Identity → RetrievalSecurityContext）合并（AND）后生效。
     */
    public RetrievalResult retrieve(RetrievalQuery rq) {
        RetrievalFilter filter = effectiveFilter(rq == null ? null : rq.filterExpression());
        Accumulator acc = search(new Accumulator(),
                rq == null ? List.of() : List.of(rq.query()), filter);
        RetrievalResult result = assemble(acc, rq == null ? null : rq.query());
        recordRetrievalMetrics(result);
        return result;
    }

    /** 合并显式表达式与当前身份过滤。 */
    private RetrievalFilter effectiveFilter(String filterExpression) {
        return RetrievalFilter.parse(filterExpression).and(RetrievalSecurityContext.identityFilter());
    }

    private static String firstNonBlank(List<String> queries) {
        if (queries == null) return "";
        for (String q : queries) {
            if (q != null && !q.isBlank()) return q;
        }
        return "";
    }

    /**
     * v4 §6.6 指标埋点：最终进入生成上下文的来源各记 RETRIEVED + CITED 一条。
     * <p>
     * 如实声明两点粒度限制：
     * 1）Source 仅暴露 docId（无父块/子块 ID），metric_event.chunk_id 列暂存 docId 作为归属键，
     *    看板按总量聚合不受影响，单 chunk 明细回查不适用该来源；
     * 2）本管道"召回即引用"，RETRIEVED/CITED 计数相同；多轮 AgentRag 路径的候选/引用区分暂未埋点。
     * 埋点失败不得影响对话主链路。
     */
    public void recordRetrievalMetrics(RetrievalResult result) {
        if (result.sources().isEmpty()) {
            return;
        }
        try {
            for (Source s : result.sources()) {
                metricEventDao.save(newMetric(s, "RETRIEVED"));
                metricEventDao.save(newMetric(s, "CITED"));
            }
        } catch (Exception e) {
            log.warn("检索指标埋点失败（不影响对话）: {}", e.getMessage());
        }
    }

    private MetricEventEntity newMetric(Source s, String eventType) {
        MetricEventEntity e = new MetricEventEntity();
        e.setChunkId(s.docId());
        e.setEventType(eventType);
        e.setSimilarity(s.score());
        return e;
    }

    public Accumulator newAccumulator() {
        return new Accumulator();
    }

    /**
     * 执行一轮多查询混合检索并累积进 acc（空查询被忽略）。
     * 同一父块跨轮/跨查询只保留最高分与首次命中顺序。
     * 自动叠加当前请求身份过滤（RetrievalSecurityContext）。
     */
    public Accumulator search(Accumulator acc, List<String> queries) {
        return search(acc, queries, effectiveFilter(null));
    }

    /**
     * E5：带权限过滤的多查询检索。关系库侧按 knowledge_metadata 行做权威过滤
     * （未打标 chunk 默认放行）；wikiagent.milvus.filter-metadata=true 且集合含
     * 标量列时同时下推 Milvus 表达式。
     */
    public Accumulator search(Accumulator acc, List<String> queries, RetrievalFilter filter) {
        if (acc == null || queries == null) {
            return acc;
        }
        RetrievalFilter effective = filter == null ? RetrievalFilter.none() : filter;
        String pushdownExpr = milvusFilterMetadata && !effective.isEmpty() ? effective.toMilvusExpr() : null;
        boolean fallback = System.currentTimeMillis() < milvusDisabledUntil;
        for (String q : queries) {
            if (q == null || q.isBlank()) {
                continue;
            }
            if (fallback) {
                localKeywordSearch(acc, q, effective);
                continue;
            }
            try {
                float[] qvec = embeddingModel.embed(q);
                List<MilvusStoreService.Hit> hits = milvus.hybridSearch(
                        qvec, q,
                        props.retrieve().subTopk(), props.retrieve().finalTopk(), props.retrieve().rrfK(),
                        pushdownExpr);
                hits = filterHitsByMetadata(hits, effective);
                for (MilvusStoreService.Hit h : hits) {
                    // §2.5：累积命中同时记录父块代表子块（最高分、平局 childIndex 小者）
                    acc.put(h.parentId(), h.docId(), h.childId(), h.childIndex(), h.score());
                }
            } catch (Exception e) {
                // Milvus 不可用时降级为 DB 关键词检索（开发零依赖 / 生产故障兜底），冷却期内不再尝试 Milvus
                log.warn("Milvus 混合检索失败，降级为本地关键词检索: {}", e.getMessage());
                milvusDisabledUntil = System.currentTimeMillis() + FALLBACK_COOLDOWN_MS;
                fallback = true;
                localKeywordSearch(acc, q, effective);
            }
        }
        return acc;
    }

    /** E5：按 knowledge_metadata 行过滤 Milvus 命中（childId → 元数据）。不过滤/无 DAO 时原样返回。 */
    private List<MilvusStoreService.Hit> filterHitsByMetadata(List<MilvusStoreService.Hit> hits,
                                                              RetrievalFilter filter) {
        if (filter.isEmpty() || metadataDao == null || hits == null || hits.isEmpty()) {
            return hits;
        }
        try {
            Map<String, KnowledgeMetadataEntity> meta = loadMetadata(
                    hits.stream().map(MilvusStoreService.Hit::childId).toList());
            List<MilvusStoreService.Hit> out = new ArrayList<>(hits.size());
            for (MilvusStoreService.Hit h : hits) {
                if (allowed(meta.get(h.childId()), filter)) {
                    out.add(h);
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("检索权限过滤失败（保守放行原结果）: {}", e.getMessage());
            return hits;
        }
    }

    private Map<String, KnowledgeMetadataEntity> loadMetadata(List<String> chunkIds) {
        Map<String, KnowledgeMetadataEntity> map = new HashMap<>();
        for (KnowledgeMetadataEntity e : metadataDao.findByChunkIdIn(chunkIds)) {
            map.put(e.getChunkId(), e);
        }
        return map;
    }

    /** 元数据行是否满足过滤；未打标（无行）默认放行。 */
    private boolean allowed(KnowledgeMetadataEntity meta, RetrievalFilter filter) {
        if (meta == null) {
            return true;
        }
        return filter.matches(meta.getDomainTag(), meta.getSubDomainTag(), meta.getRequiredIdentity());
    }

    /**
     * 本地关键词降级检索（Milvus 不可用时）：
     * 查询切分为中文 bigram + 空白分词，对 kb_child_chunk 做 LIKE 召回，
     * 按命中词数在内存打分，取 subTopk 个子块映射到父块累积。
     * <p>
     * <b>诚实声明</b>：这是无向量/无 BM25 索引时的开发降级，相关性弱于 Milvus hybridSearch，
     * 仅保证"无外部依赖可用 + 能命中字面重合知识"，不声称等价。
     */
    private void localKeywordSearch(Accumulator acc, String query) {
        localKeywordSearch(acc, query, RetrievalFilter.none());
    }

    private void localKeywordSearch(Accumulator acc, String query, RetrievalFilter filter) {
        List<String> terms = extractTerms(query);
        if (terms.isEmpty()) {
            return;
        }
        int perTermLimit = Math.max(20, props.retrieve().subTopk() * 2);
        Map<KbChildChunk, Integer> chunkTerms = new HashMap<>();
        for (String term : terms) {
            List<KbChildChunk> rows;
            try {
                rows = childRepo.findByContentContainingIgnoreCaseAndActiveTrue(
                        term, PageRequest.of(0, perTermLimit));
            } catch (Exception e) {
                log.warn("本地关键词检索失败 term={}: {}", term, e.getMessage());
                continue;
            }
            for (KbChildChunk c : rows) {
                chunkTerms.merge(c, 1, Integer::sum);
            }
        }
        // E5：权限过滤（按子块元数据）
        if (!filter.isEmpty() && metadataDao != null && !chunkTerms.isEmpty()) {
            try {
                Map<String, KnowledgeMetadataEntity> meta = loadMetadata(
                        chunkTerms.keySet().stream().map(KbChildChunk::getId).toList());
                chunkTerms.keySet().removeIf(c -> !allowed(meta.get(c.getId()), filter));
            } catch (Exception e) {
                log.warn("本地检索权限过滤失败（保守放行）: {}", e.getMessage());
            }
        }
        chunkTerms.entrySet().stream()
                .sorted(Comparator.<Map.Entry<KbChildChunk, Integer>>comparingInt(Map.Entry::getValue).reversed())
                .limit(props.retrieve().subTopk())
                .forEach(e -> {
                    KbChildChunk c = e.getKey();
                    double score = (double) e.getValue() / terms.size(); // 命中词占比，0~1
                    // §2.5：本地降级路径同样记录代表子块，保证引用六字段两条路径一致
                    acc.put(c.getParentId(), c.getDocId(), c.getId(), c.getChildIndex(), score);
                });
    }

    /** 中文 bigram + 拉丁词切分（去重、限量）。 */
    private List<String> extractTerms(String query) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        String trimmed = query.trim();
        if (trimmed.length() == 1) {
            terms.add(trimmed);
            return new ArrayList<>(terms);
        }
        // 拉丁/数字词
        for (String w : trimmed.split("[^A-Za-z0-9_]+")) {
            if (w.length() >= 2) {
                terms.add(w.toLowerCase());
            }
        }
        // 中文（及其他非空白字符）bigram
        char[] chars = trimmed.toCharArray();
        for (int i = 0; i + 1 < chars.length && terms.size() < FALLBACK_MAX_TERMS; i++) {
            if (isCjk(chars[i]) && isCjk(chars[i + 1])) {
                terms.add(trimmed.substring(i, i + 2));
            }
        }
        return new ArrayList<>(terms);
    }

    private boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    /**
     * E2 rerank：可用时对候选父块按 rerank 分数重排 parentOrder。
     * 候选父块内容缺失时跳过；任何异常不阻断，保持原顺序。
     * E3：实际调用 provider 时写 purpose=RERANK 的 model_call_log（成功/失败各一行）；
     * rerank 未启用/不可用时本方法直接返回——没调就不写日志（诚实打点）。
     */
    private void maybeRerank(Accumulator acc, String query) {
        if (rerankProvider == null || query == null || query.isBlank() || acc.parentOrder.size() < 2) {
            return;
        }
        String provider = "dashscope";
        String model = rerankProvider instanceof com.wikiagent.infrastructure.llm.DashScopeRerankProvider d
                ? d.model() : rerankProvider.name();
        long started = System.currentTimeMillis();
        try {
            List<String> parentIds = new ArrayList<>(acc.parentOrder);
            Map<String, KbParentChunk> parents = new HashMap<>();
            for (KbParentChunk p : parentRepo.findAllById(parentIds)) {
                parents.put(p.getId(), p);
            }
            List<String> docs = new ArrayList<>(parentIds.size());
            List<String> validIds = new ArrayList<>(parentIds.size());
            for (String pid : parentIds) {
                KbParentChunk p = parents.get(pid);
                if (p == null) continue;
                docs.add(p.getContent());
                validIds.add(pid);
            }
            if (docs.size() < 2) {
                return;
            }
            RerankResult rr = rerankProvider.rerank(new RerankRequest(query, docs, docs.size()));
            if (rr == null || rr.scores() == null || rr.scores().size() != docs.size()) {
                return;
            }
            recordRerank(provider, model, System.currentTimeMillis() - started, true);
            List<String> reordered = new ArrayList<>(validIds);
            List<Double> scores = rr.scores();
            // 按分数降序稳定重排
            List<Integer> idx = new ArrayList<>(validIds.size());
            for (int i = 0; i < validIds.size(); i++) idx.add(i);
            idx.sort((a, b) -> Double.compare(scores.get(b), scores.get(a)));
            reordered.clear();
            for (int i : idx) reordered.add(validIds.get(i));
            acc.parentOrder.clear();
            acc.parentOrder.addAll(reordered);
            // bestScore 同步为 rerank 分数（保留两位小数精度语义不重要，取原值）
            for (int i = 0; i < validIds.size(); i++) {
                acc.bestScore.put(validIds.get(i), scores.get(i));
            }
        } catch (Exception e) {
            recordRerank(provider, model, System.currentTimeMillis() - started, false);
            log.warn("rerank 失败，保持原序: {}", e.getMessage());
        }
    }

    /** E3：RERANK 打点；callRecorder 缺失（旧装配/单测）时静默跳过。 */
    private void recordRerank(String provider, String model, long latencyMs, boolean success) {
        if (callRecorder != null) {
            callRecorder.record(ModelCallLogPurpose.RERANK, provider, model,
                    null, null, latencyMs, success, null, null, null);
        }
    }

    /** 累积器 → 父文档上下文：H2 回查父块全文与来源文件名，字符预算内按命中顺序编号组装。 */
    public RetrievalResult assemble(Accumulator acc) {
        return assemble(acc, null);
    }

    /**
     * E2：带 rerank 的组装。rerankProvider 可用时按 query 对候选父块重排，
     * 重排失败/不可用不阻断，按原序返回。
     */
    public RetrievalResult assemble(Accumulator acc, String rerankQuery) {
        if (acc.parentOrder.isEmpty()) {
            return new RetrievalResult(List.of(), "");
        }
        maybeRerank(acc, rerankQuery);

        // H2 回查父块全文与来源文件名（事实源）
        Map<String, KbParentChunk> parents = new HashMap<>();
        for (KbParentChunk p : parentRepo.findAllById(acc.parentOrder)) {
            parents.put(p.getId(), p);
        }
        Set<String> docIds = new LinkedHashSet<>(acc.parentDoc.values());
        Map<String, String> docNames = new HashMap<>();
        for (KbDocument d : docRepo.findAllById(docIds)) {
            docNames.put(d.getId(), d.getFilename());
        }

        // §2.5：批量回查每个父块的代表子块，取 versionNo/pageNo/snippet
        Map<String, KbChildChunk> repChildren = new HashMap<>();
        if (!acc.repChild.isEmpty()) {
            for (KbChildChunk c : childRepo.findByIdIn(
                    acc.repChild.values().stream().map(Accumulator.RepChild::childId).toList())) {
                repChildren.put(c.getId(), c);
            }
        }

        int budget = props.retrieve().parentCharBudget();
        StringBuilder ctx = new StringBuilder();
        List<Source> sources = new ArrayList<>();
        int used = 0;
        int idx = 1;
        for (String parentId : acc.parentOrder) {
            KbParentChunk p = parents.get(parentId);
            if (p == null) {
                continue; // Milvus 与 H2 不一致（如刚被删除），跳过
            }
            String content = p.getContent();
            int remaining = budget - used;
            if (remaining <= 200) {
                break;
            }
            if (content.length() > remaining) {
                content = content.substring(0, remaining);
            }
            String filename = docNames.getOrDefault(p.getDocId(), "未知文档");
            ctx.append('[').append(idx).append("] 来源: ").append(filename).append('\n')
                    .append(content).append("\n\n");
            used += content.length();
            Accumulator.RepChild rc = acc.repChild.get(parentId);
            KbChildChunk child = rc == null ? null : repChildren.get(rc.childId());
            sources.add(new Source(idx, p.getDocId(),
                    child != null ? child.getVersionNo() : 0,
                    child != null ? child.getPageNo() : null,
                    snippetOf(child == null ? null : child.getContent()),
                    null, // artifactId 预留：kb_child_chunk 无该列，后续由 knowledge_metadata 回填
                    acc.bestScore.getOrDefault(parentId, 0.0), filename));
            idx++;
        }
        return new RetrievalResult(sources, ctx.toString());
    }

    /** §2.5：引用片段 = 子块原文去空白后前 200 字符；子块缺失/空时为 null。 */
    private static String snippetOf(String childContent) {
        if (childContent == null) {
            return null;
        }
        String s = childContent.strip();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
