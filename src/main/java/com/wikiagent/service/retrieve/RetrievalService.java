package com.wikiagent.service.retrieve;

import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.entity.KbDocument;
import com.wikiagent.entity.KbParentChunk;
import com.wikiagent.repo.KbDocumentRepo;
import com.wikiagent.repo.KbParentChunkRepo;
import com.wikiagent.service.store.MilvusStoreService;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 混合检索：查询向量化 → Milvus 双路召回（BM25 + 向量，服务端 RRF 融合）
 * → top10 子块替换为父文档（按命中顺序去重、字符预算内组装上下文）。
 */
@Service
public class RetrievalService {

    public record Source(int index, String docId, String filename, double score) {
    }

    /** context 为空表示知识库中没有检索到相关内容。 */
    public record RetrievalResult(List<Source> sources, String context) {
    }

    private final WikiAgentProperties props;
    private final MilvusStoreService milvus;
    private final EmbeddingModel embeddingModel;
    private final KbParentChunkRepo parentRepo;
    private final KbDocumentRepo docRepo;

    public RetrievalService(WikiAgentProperties props, MilvusStoreService milvus, EmbeddingModel embeddingModel,
                            KbParentChunkRepo parentRepo, KbDocumentRepo docRepo) {
        this.props = props;
        this.milvus = milvus;
        this.embeddingModel = embeddingModel;
        this.parentRepo = parentRepo;
        this.docRepo = docRepo;
    }

    public RetrievalResult retrieve(String query) {
        float[] qvec = embeddingModel.embed(query);
        List<MilvusStoreService.Hit> hits = milvus.hybridSearch(
                qvec, query,
                props.retrieve().subTopk(), props.retrieve().finalTopk(), props.retrieve().rrfK());
        if (hits.isEmpty()) {
            return new RetrievalResult(List.of(), "");
        }

        // 子块 → 父文档：按 RRF 命中顺序去重，保留每父块最高分
        LinkedHashSet<String> parentOrder = new LinkedHashSet<>();
        Map<String, Double> bestScore = new HashMap<>();
        Map<String, String> parentDoc = new HashMap<>();
        for (MilvusStoreService.Hit h : hits) {
            parentOrder.add(h.parentId());
            bestScore.merge(h.parentId(), h.score(), Math::max);
            parentDoc.putIfAbsent(h.parentId(), h.docId());
        }

        // H2 回查父块全文与来源文件名（事实源）
        Map<String, KbParentChunk> parents = new HashMap<>();
        for (KbParentChunk p : parentRepo.findAllById(parentOrder)) {
            parents.put(p.getId(), p);
        }
        Set<String> docIds = new LinkedHashSet<>(parentDoc.values());
        Map<String, String> docNames = new HashMap<>();
        for (KbDocument d : docRepo.findAllById(docIds)) {
            docNames.put(d.getId(), d.getFilename());
        }

        int budget = props.retrieve().parentCharBudget();
        StringBuilder ctx = new StringBuilder();
        List<Source> sources = new ArrayList<>();
        int used = 0;
        int idx = 1;
        for (String parentId : parentOrder) {
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
            sources.add(new Source(idx, p.getDocId(), filename, bestScore.getOrDefault(parentId, 0.0)));
            idx++;
        }
        return new RetrievalResult(sources, ctx.toString());
    }
}
