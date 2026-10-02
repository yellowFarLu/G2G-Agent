package com.wikiagent.application.eval.suite;

import com.wikiagent.application.eval.EvalGoldenLoader;
import com.wikiagent.application.eval.support.InstanceObjectProvider;
import com.wikiagent.application.eval.support.ThrowingEmbeddingModel;
import com.wikiagent.application.eval.support.UnavailableMilvusStoreService;
import com.wikiagent.application.llm.ModelCallRecorder;
import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.domain.eval.EvalCategory;
import com.wikiagent.domain.eval.SampleResult;
import com.wikiagent.domain.eval.data.CitationSample;
import com.wikiagent.domain.eval.golden.RetrieveGoldenCase;
import com.wikiagent.domain.llm.spi.RerankProvider;
import com.wikiagent.domain.retrieve.RetrievalQuery;
import com.wikiagent.entity.KbChildChunk;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataJpaDao;
import com.wikiagent.infrastructure.persistence.MetricEventJpaDao;
import com.wikiagent.repo.KbChildChunkRepo;
import com.wikiagent.repo.KbDocumentRepo;
import com.wikiagent.repo.KbParentChunkRepo;
import com.wikiagent.service.retrieve.RetrievalService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RETRIEVE 评测套件（AC-H1/H2）：真实 {@link RetrievalService}，
 * Milvus/Embedding 用“立即失败”端口强制走<b>本地关键词降级</b>（bigram LIKE），
 * 数据由 EvalSeeder 从 retrieve-seed.json 播种，零 Milvus/零 Docker。
 * <p>
 * 断言在 docId 粒度（现网 Source 仅暴露 docId）；同时逐条 Source 判定引用可解析/一致性，
 * 产出 CitationSample 供 citationCorrectRate 指标计算。检索过程由 RetrievalService
 * 真实埋点 RETRIEVED/CITED（供 retrievalHitRate，相关性由 kb_feedback 种子标注）。
 */
public class RetrieveEvalSuite {

    private final EvalGoldenLoader loader;
    private final KbParentChunkRepo parentRepo;
    private final KbDocumentRepo docRepo;
    private final KbChildChunkRepo childRepo;
    private final KnowledgeMetadataJpaDao metadataDao;
    private final MetricEventJpaDao metricEventDao;

    public RetrieveEvalSuite(EvalGoldenLoader loader,
                             KbParentChunkRepo parentRepo,
                             KbDocumentRepo docRepo,
                             KbChildChunkRepo childRepo,
                             KnowledgeMetadataJpaDao metadataDao,
                             MetricEventJpaDao metricEventDao) {
        this.loader = loader;
        this.parentRepo = parentRepo;
        this.docRepo = docRepo;
        this.childRepo = childRepo;
        this.metadataDao = metadataDao;
        this.metricEventDao = metricEventDao;
    }

    public SuiteOutput run() {
        RetrievalService service = newRetrievalService();
        List<SampleResult> results = new ArrayList<>();
        List<CitationSample> citations = new ArrayList<>();
        for (RetrieveGoldenCase c : loader.loadRetrieveGolden()) {
            results.add(runOne(service, c, citations));
        }
        return new SuiteOutput(results, citations);
    }

    private RetrievalService newRetrievalService() {
        WikiAgentProperties props = new WikiAgentProperties(null,
                new WikiAgentProperties.Retrieve(20, 10, 60, 12000), null, null);
        return new RetrievalService(props,
                new UnavailableMilvusStoreService(props),
                new ThrowingEmbeddingModel(),
                parentRepo, docRepo, childRepo, metricEventDao,
                new InstanceObjectProvider<RerankProvider>(null),
                new InstanceObjectProvider<>(metadataDao),
                false,
                // 评测不接灰度门控（保持评测确定性，全量口径）
                new InstanceObjectProvider<ModelCallRecorder>(null),
                null);
    }

    private SampleResult runOne(RetrievalService service, RetrieveGoldenCase c,
                                List<CitationSample> citations) {
        List<String> failures = new ArrayList<>();
        Map<String, Object> metrics = new LinkedHashMap<>();
        try {
            Map<String, String> chunkToDoc = chunkDocMap(
                    concat(c.expectedChunkIds(), c.forbiddenChunkIds()));

            RetrievalService.RetrievalResult result =
                    service.retrieve(RetrievalQuery.of(c.query(), 1));
            Set<String> retrievedDocs = new HashSet<>();
            for (RetrievalService.Source s : result.sources()) {
                retrievedDocs.add(s.docId());
                citations.add(judgeCitation(s));
            }

            Set<String> expectedDocs = new HashSet<>();
            c.expectedChunkIds().forEach(id -> {
                String d = chunkToDoc.get(id);
                if (d != null) {
                    expectedDocs.add(d);
                }
            });
            Set<String> forbiddenDocs = new HashSet<>();
            if (c.forbiddenChunkIds() != null) {
                c.forbiddenChunkIds().forEach(id -> {
                    String d = chunkToDoc.get(id);
                    if (d != null) {
                        forbiddenDocs.add(d);
                    }
                });
            }

            long hit = expectedDocs.stream().filter(retrievedDocs::contains).count();
            for (String d : expectedDocs) {
                if (!retrievedDocs.contains(d)) {
                    failures.add("期望文档未命中: " + d);
                }
            }
            for (String d : forbiddenDocs) {
                if (retrievedDocs.contains(d)) {
                    failures.add("禁止文档被召回（软删失效）: " + d);
                }
            }
            metrics.put("retrievedDocIds", retrievedDocs);
            metrics.put("expectedDocIds", expectedDocs);
            metrics.put("forbiddenDocIds", forbiddenDocs);
            metrics.put("sourceCount", result.sources().size());
            metrics.put("expectedHit", hit);
            metrics.put("expectedTotal", expectedDocs.size());

            double score = expectedDocs.isEmpty() ? 0.0 : (double) hit / expectedDocs.size();
            if (failures.isEmpty()) {
                return SampleResult.passed(c.id(), EvalCategory.RETRIEVE, score, metrics);
            }
            return SampleResult.failed(c.id(), EvalCategory.RETRIEVE, score, metrics,
                    String.join("；", failures));
        } catch (Exception e) {
            return SampleResult.error(c.id(), EvalCategory.RETRIEVE,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 逐条 Source 判定引用：versionNo 可解析到 active 子块 + pageNo/snippet 与子块一致。 */
    private CitationSample judgeCitation(RetrievalService.Source s) {
        List<KbChildChunk> candidates = childRepo.findByDocIdAndActiveTrue(s.docId());
        boolean resolvable = candidates.stream()
                .anyMatch(ch -> ch.getVersionNo() == s.versionNo());
        boolean consistent = candidates.stream().anyMatch(ch ->
                (s.pageNo() == null || s.pageNo().equals(ch.getPageNo()))
                        && s.snippet() != null
                        && ch.getContent() != null
                        && ch.getContent().strip().contains(s.snippet()));
        return new CitationSample(s.docId(), s.versionNo(), s.pageNo(),
                resolvable, consistent, Instant.now());
    }

    private Map<String, String> chunkDocMap(List<String> chunkIds) {
        Map<String, String> map = new LinkedHashMap<>();
        childRepo.findAllById(chunkIds).forEach(ch -> map.put(ch.getId(), ch.getDocId()));
        return map;
    }

    private List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a == null ? List.of() : a);
        if (b != null) {
            all.addAll(b);
        }
        return all;
    }
}
