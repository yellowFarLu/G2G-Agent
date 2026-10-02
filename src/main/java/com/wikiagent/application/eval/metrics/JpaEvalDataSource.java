package com.wikiagent.application.eval.metrics;

import com.wikiagent.domain.eval.data.CitationSample;
import com.wikiagent.domain.eval.data.EvalDataSource;
import com.wikiagent.domain.eval.data.FieldSample;
import com.wikiagent.domain.eval.data.JudgeVerdict;
import com.wikiagent.domain.eval.data.ModelCallSample;
import com.wikiagent.domain.eval.data.RetrievalSample;
import com.wikiagent.domain.eval.data.ReviewDisposition;
import com.wikiagent.entity.KbChildChunk;
import com.wikiagent.entity.lineage.ExtractedFieldEntity;
import com.wikiagent.entity.rule.ReviewCaseEntity;
import com.wikiagent.infrastructure.persistence.KbFeedbackEntity;
import com.wikiagent.infrastructure.persistence.KbFeedbackJpaDao;
import com.wikiagent.infrastructure.persistence.MetricEventEntity;
import com.wikiagent.infrastructure.persistence.MetricEventJpaDao;
import com.wikiagent.infrastructure.persistence.llm.ModelCallLogEntity;
import com.wikiagent.infrastructure.persistence.llm.ModelCallLogJpaDao;
import com.wikiagent.repo.KbChildChunkRepo;
import com.wikiagent.repo.lineage.ExtractedFieldRepo;
import com.wikiagent.repo.rule.ReviewCaseRepo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link EvalDataSource} 的 JPA 只读适配器：从既有 DAO 全量读取后在内存投影/过滤，
 * 不修改任何既有 DAO/实体（遵守子项目 H 文件所有权约束）。
 * <p>
 * 相关性标签：kb_feedback（USEFUL=true/USELESS=false）按 chunkId(=docId 粒度) 聚合；
 * 无反馈的 RETRIEVED 行 relevant=null，不进命中率分母（禁止伪造标签）。
 * citations/judgeVerdicts 由评测运行器在内存中累积/加载后传入。
 */
public class JpaEvalDataSource implements EvalDataSource {

    private final ExtractedFieldRepo fieldRepo;
    private final MetricEventJpaDao metricEventDao;
    private final KbFeedbackJpaDao feedbackDao;
    private final ReviewCaseRepo reviewCaseRepo;
    private final ModelCallLogJpaDao modelCallDao;
    private final KbChildChunkRepo childChunkRepo;
    private final List<CitationSample> citations;
    private final List<JudgeVerdict> judgeVerdicts;

    public JpaEvalDataSource(ExtractedFieldRepo fieldRepo,
                             MetricEventJpaDao metricEventDao,
                             KbFeedbackJpaDao feedbackDao,
                             ReviewCaseRepo reviewCaseRepo,
                             ModelCallLogJpaDao modelCallDao,
                             KbChildChunkRepo childChunkRepo,
                             List<CitationSample> citations,
                             List<JudgeVerdict> judgeVerdicts) {
        this.fieldRepo = fieldRepo;
        this.metricEventDao = metricEventDao;
        this.feedbackDao = feedbackDao;
        this.reviewCaseRepo = reviewCaseRepo;
        this.modelCallDao = modelCallDao;
        this.childChunkRepo = childChunkRepo;
        this.citations = citations == null ? List.of() : citations;
        this.judgeVerdicts = judgeVerdicts == null ? List.of() : judgeVerdicts;
    }

    @Override
    public List<FieldSample> fields() {
        List<FieldSample> out = new ArrayList<>();
        for (ExtractedFieldEntity f : fieldRepo.findAll()) {
            out.add(new FieldSample(f.getDocId(), f.getFieldKey(), f.isValid(), f.getCreatedAt()));
        }
        return out;
    }

    @Override
    public List<RetrievalSample> retrievals() {
        Map<String, Boolean> relevance = loadRelevance();
        List<RetrievalSample> out = new ArrayList<>();
        for (MetricEventEntity e : metricEventDao.findAll()) {
            out.add(new RetrievalSample(e.getChunkId(),
                    relevance.get(e.getChunkId()), e.getEventType(), e.getCreatedAt()));
        }
        return out;
    }

    @Override
    public List<CitationSample> citations() {
        return citations;
    }

    @Override
    public List<JudgeVerdict> judgeVerdicts() {
        return judgeVerdicts;
    }

    @Override
    public List<ReviewDisposition> reviewDispositions() {
        List<ReviewDisposition> out = new ArrayList<>();
        for (ReviewCaseEntity c : reviewCaseRepo.findAll()) {
            out.add(new ReviewDisposition(
                    String.valueOf(c.getId()), c.getStatus(),
                    c.getResolvedAt() != null ? c.getResolvedAt() : c.getCreatedAt()));
        }
        return out;
    }

    @Override
    public List<ModelCallSample> modelCalls() {
        List<ModelCallSample> out = new ArrayList<>();
        for (ModelCallLogEntity c : modelCallDao.findAll()) {
            out.add(new ModelCallSample(c.getPurpose(), c.getProvider(), c.getModel(),
                    c.getLatencyMs(), c.getCostEstimate(), c.getStatus(), c.getCreatedAt()));
        }
        return out;
    }

    /**
     * kb_feedback → chunkId 相关性（任一 USEFUL 记 true；否则有 USELESS 记 false）。
     * <p>
     * 只采纳 eval- 前缀的评测种子反馈：评测运行可能跑在共享开发库上，bigram 降级会
     * 额外召回业务/其它测试 chunk（其 RETRIEVED 事件 relevant 保持 null 不进分母），
     * 但它们身上的历史反馈标签不得污染本次离线评测口径。
     */
    private Map<String, Boolean> loadRelevance() {
        Map<String, Boolean> useful = new HashMap<>();
        Map<String, Boolean> useless = new HashMap<>();
        for (KbFeedbackEntity f : feedbackDao.findAll()) {
            if (f.getChunkId() == null || !f.getChunkId().startsWith("eval-doc")) {
                continue;
            }
            if ("USEFUL".equalsIgnoreCase(f.getFeedbackType())) {
                useful.put(f.getChunkId(), Boolean.TRUE);
            } else if ("USELESS".equalsIgnoreCase(f.getFeedbackType())) {
                useless.put(f.getChunkId(), Boolean.TRUE);
            }
        }
        Map<String, Boolean> merged = new HashMap<>();
        useless.keySet().forEach(k -> merged.put(k, Boolean.FALSE));
        useful.forEach(merged::put); // USEFUL 优先（同一 doc 同时存在两类反馈时按正信号计）
        return merged;
    }

    /** 评测运行器装配引用判定时可复用的子块只读查询。 */
    public List<KbChildChunk> activeChildrenOfDoc(String docId) {
        return childChunkRepo.findByDocIdAndActiveTrue(docId);
    }
}
