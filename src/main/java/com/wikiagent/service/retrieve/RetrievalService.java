package com.wikiagent.service.retrieve;

import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.entity.KbChildChunk;
import com.wikiagent.entity.KbDocument;
import com.wikiagent.entity.KbParentChunk;
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

    public record Source(int index, String docId, String filename, double score) {
    }

    /** context 为空表示知识库中没有检索到相关内容。 */
    public record RetrievalResult(List<Source> sources, String context) {
    }

    /** 多轮检索累积器：按父块 ID 去重的命中集合。 */
    public static final class Accumulator {
        private final LinkedHashSet<String> parentOrder = new LinkedHashSet<>();
        private final Map<String, Double> bestScore = new HashMap<>();
        private final Map<String, String> parentDoc = new HashMap<>();
    }

    private final WikiAgentProperties props;
    private final MilvusStoreService milvus;
    private final EmbeddingModel embeddingModel;
    private final KbParentChunkRepo parentRepo;
    private final KbDocumentRepo docRepo;
    private final KbChildChunkRepo childRepo;
    private final MetricEventJpaDao metricEventDao;

    /** Milvus 不可用截止时间戳；0 表示正常，>now 表示冷却降级中。 */
    private volatile long milvusDisabledUntil = 0L;

    public RetrievalService(WikiAgentProperties props, MilvusStoreService milvus, EmbeddingModel embeddingModel,
                            KbParentChunkRepo parentRepo, KbDocumentRepo docRepo, KbChildChunkRepo childRepo,
                            MetricEventJpaDao metricEventDao) {
        this.props = props;
        this.milvus = milvus;
        this.embeddingModel = embeddingModel;
        this.parentRepo = parentRepo;
        this.docRepo = docRepo;
        this.childRepo = childRepo;
        this.metricEventDao = metricEventDao;
    }

    /** 单次多查询检索（一次组装）。 */
    public RetrievalResult retrieve(List<String> queries) {
        RetrievalResult result = assemble(search(new Accumulator(), queries));
        recordRetrievalMetrics(result);
        return result;
    }

    /** 单查询单次检索（兼容旧链路）。 */
    public RetrievalResult retrieve(String query) {
        return retrieve(List.of(query));
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
     */
    public Accumulator search(Accumulator acc, List<String> queries) {
        if (acc == null || queries == null) {
            return acc;
        }
        boolean fallback = System.currentTimeMillis() < milvusDisabledUntil;
        for (String q : queries) {
            if (q == null || q.isBlank()) {
                continue;
            }
            if (fallback) {
                localKeywordSearch(acc, q);
                continue;
            }
            try {
                float[] qvec = embeddingModel.embed(q);
                List<MilvusStoreService.Hit> hits = milvus.hybridSearch(
                        qvec, q,
                        props.retrieve().subTopk(), props.retrieve().finalTopk(), props.retrieve().rrfK());
                for (MilvusStoreService.Hit h : hits) {
                    acc.parentOrder.add(h.parentId());
                    acc.bestScore.merge(h.parentId(), h.score(), Math::max);
                    acc.parentDoc.putIfAbsent(h.parentId(), h.docId());
                }
            } catch (Exception e) {
                // Milvus 不可用时降级为 DB 关键词检索（开发零依赖 / 生产故障兜底），冷却期内不再尝试 Milvus
                log.warn("Milvus 混合检索失败，降级为本地关键词检索: {}", e.getMessage());
                milvusDisabledUntil = System.currentTimeMillis() + FALLBACK_COOLDOWN_MS;
                fallback = true;
                localKeywordSearch(acc, q);
            }
        }
        return acc;
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
        chunkTerms.entrySet().stream()
                .sorted(Comparator.<Map.Entry<KbChildChunk, Integer>>comparingInt(Map.Entry::getValue).reversed())
                .limit(props.retrieve().subTopk())
                .forEach(e -> {
                    KbChildChunk c = e.getKey();
                    double score = (double) e.getValue() / terms.size(); // 命中词占比，0~1
                    acc.parentOrder.add(c.getParentId());
                    acc.bestScore.merge(c.getParentId(), score, Math::max);
                    acc.parentDoc.putIfAbsent(c.getParentId(), c.getDocId());
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

    /** 累积器 → 父文档上下文：H2 回查父块全文与来源文件名，字符预算内按命中顺序编号组装。 */
    public RetrievalResult assemble(Accumulator acc) {
        if (acc.parentOrder.isEmpty()) {
            return new RetrievalResult(List.of(), "");
        }

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
            sources.add(new Source(idx, p.getDocId(), filename, acc.bestScore.getOrDefault(parentId, 0.0)));
            idx++;
        }
        return new RetrievalResult(sources, ctx.toString());
    }
}
