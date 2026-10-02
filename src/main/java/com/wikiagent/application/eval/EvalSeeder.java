package com.wikiagent.application.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.wikiagent.entity.KbChildChunk;
import com.wikiagent.entity.KbDocument;
import com.wikiagent.entity.KbParentChunk;
import com.wikiagent.entity.lineage.ExtractedFieldEntity;
import com.wikiagent.entity.rule.ReviewCaseEntity;
import com.wikiagent.infrastructure.persistence.KbFeedbackJpaDao;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataEntity;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataJpaDao;
import com.wikiagent.infrastructure.persistence.MetricEventEntity;
import com.wikiagent.infrastructure.persistence.MetricEventJpaDao;
import com.wikiagent.infrastructure.persistence.llm.ModelCallLogEntity;
import com.wikiagent.infrastructure.persistence.llm.ModelCallLogJpaDao;
import com.wikiagent.repo.KbChildChunkRepo;
import com.wikiagent.repo.KbDocumentRepo;
import com.wikiagent.repo.KbParentChunkRepo;
import com.wikiagent.repo.lineage.ExtractedFieldRepo;
import com.wikiagent.repo.rule.ReviewCaseRepo;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 离线评测幂等造数器：先按 {@code eval-} 前缀删除既有评测行（可重复运行），
 * 再把 {@code seed/retrieve-seed.json} 装配为真实 JPA 实体写入。
 * <p>
 * 只触碰 eval- 前缀数据，不碰任何业务行；零外部依赖（H2/MySQL 均可）。
 * 时间字段统一解析 "NOW" 哨兵为运行时刻，保证落入本次评测时间窗。
 */
@Component
public class EvalSeeder {

    public static final String NOW = "NOW";

    private final KbDocumentRepo docRepo;
    private final KbParentChunkRepo parentRepo;
    private final KbChildChunkRepo childRepo;
    private final KnowledgeMetadataJpaDao metadataDao;
    private final MetricEventJpaDao metricEventDao;
    private final KbFeedbackJpaDao feedbackDao;
    private final ExtractedFieldRepo fieldRepo;
    private final ReviewCaseRepo reviewCaseRepo;
    private final ModelCallLogJpaDao modelCallDao;

    public EvalSeeder(KbDocumentRepo docRepo,
                      KbParentChunkRepo parentRepo,
                      KbChildChunkRepo childRepo,
                      KnowledgeMetadataJpaDao metadataDao,
                      MetricEventJpaDao metricEventDao,
                      KbFeedbackJpaDao feedbackDao,
                      ExtractedFieldRepo fieldRepo,
                      ReviewCaseRepo reviewCaseRepo,
                      ModelCallLogJpaDao modelCallDao) {
        this.docRepo = docRepo;
        this.parentRepo = parentRepo;
        this.childRepo = childRepo;
        this.metadataDao = metadataDao;
        this.metricEventDao = metricEventDao;
        this.feedbackDao = feedbackDao;
        this.fieldRepo = fieldRepo;
        this.reviewCaseRepo = reviewCaseRepo;
        this.modelCallDao = modelCallDao;
    }

    @Transactional
    public void seed(JsonNode root) {
        Instant now = Instant.now();
        cleanup();
        seedDocuments(root.path("documents"));
        seedParents(root.path("parents"));
        seedChildren(root.path("children"));
        seedMetadata(root.path("metadata"), now);
        seedFeedback(root.path("feedback"), now);
        seedFields(root.path("fields"), now);
        seedReviewCases(root.path("reviewCases"), now);
        seedModelCalls(root.path("modelCalls"), now);
    }

    /** 删除全部 eval- 前缀评测行（含上次运行 RetrievalService 写入的 metric_event）。 */
    private void cleanup() {
        modelCallDao.findAll().stream()
                .filter(c -> c.getModel() != null && c.getModel().startsWith("eval-"))
                .forEach(modelCallDao::delete);
        modelCallDao.flush();
        reviewCaseRepo.findAll().stream()
                .filter(r -> r.getDocId() != null && r.getDocId().startsWith("eval-doc-"))
                .forEach(reviewCaseRepo::delete);
        reviewCaseRepo.flush();
        fieldRepo.findAll().stream()
                .filter(f -> f.getDocId() != null && f.getDocId().startsWith("eval-doc-"))
                .forEach(fieldRepo::delete);
        fieldRepo.flush();
        feedbackDao.findAll().stream()
                .filter(f -> f.getChunkId() != null && f.getChunkId().startsWith("eval-doc"))
                .forEach(feedbackDao::delete);
        feedbackDao.flush();
        metricEventDao.findAll().stream()
                .filter(e -> e.getChunkId() != null && e.getChunkId().startsWith("eval-doc"))
                .forEach(metricEventDao::delete);
        metricEventDao.flush();
        metadataDao.findAll().stream()
                .filter(m -> m.getChunkId() != null && m.getChunkId().startsWith("eval-c-"))
                .forEach(metadataDao::delete);
        metadataDao.flush();
        childRepo.findAll().stream()
                .filter(c -> c.getId().startsWith("eval-c-"))
                .forEach(childRepo::delete);
        childRepo.flush();
        parentRepo.findAll().stream()
                .filter(p -> p.getId().startsWith("eval-p-"))
                .forEach(parentRepo::delete);
        parentRepo.flush();
        docRepo.findAll().stream()
                .filter(d -> d.getId().startsWith("eval-doc-"))
                .forEach(docRepo::delete);
        docRepo.flush();
    }

    private void seedDocuments(JsonNode rows) {
        List<KbDocument> docs = new ArrayList<>();
        for (JsonNode r : rows) {
            KbDocument d = new KbDocument();
            d.setId(r.path("id").asText());
            d.setFilename(r.path("filename").asText());
            d.setDocType(r.path("docType").asText("pdf"));
            d.setSizeBytes(r.path("sizeBytes").asLong(0));
            d.setStatus(r.path("status").asText(KbDocument.READY));
            d.setParentCount(r.path("parentCount").asInt(0));
            d.setChildCount(r.path("childCount").asInt(0));
            docs.add(d);
        }
        docRepo.saveAll(docs);
    }

    private void seedParents(JsonNode rows) {
        List<KbParentChunk> list = new ArrayList<>();
        for (JsonNode r : rows) {
            KbParentChunk p = new KbParentChunk();
            p.setId(r.path("id").asText());
            p.setDocId(r.path("docId").asText());
            p.setParentIndex(r.path("parentIndex").asInt(0));
            p.setContent(r.path("content").asText(""));
            list.add(p);
        }
        parentRepo.saveAll(list);
    }

    private void seedChildren(JsonNode rows) {
        List<KbChildChunk> list = new ArrayList<>();
        for (JsonNode r : rows) {
            KbChildChunk c = new KbChildChunk();
            c.setId(r.path("id").asText());
            c.setDocId(r.path("docId").asText());
            c.setParentId(r.path("parentId").asText());
            c.setChildIndex(r.path("childIndex").asInt(0));
            if (r.hasNonNull("pageNo")) {
                c.setPageNo(r.get("pageNo").asInt());
            }
            c.setVersionNo(r.path("versionNo").asInt(1));
            c.setActive(r.path("active").asBoolean(true));
            c.setContent(r.path("content").asText(""));
            list.add(c);
        }
        childRepo.saveAll(list);
    }

    private void seedMetadata(JsonNode rows, Instant now) {
        List<KnowledgeMetadataEntity> list = new ArrayList<>();
        for (JsonNode r : rows) {
            KnowledgeMetadataEntity m = new KnowledgeMetadataEntity();
            m.setChunkId(r.path("chunkId").asText());
            m.setDocId(r.path("docId").asText());
            m.setDomainTag(r.path("domainTag").asText());
            m.setSubDomainTag(r.path("subDomainTag").asText());
            m.setRequiredIdentity(r.path("requiredIdentity").asText("*"));
            m.setVersion(r.path("version").asInt(1));
            m.setIsActive(r.path("isActive").asBoolean(true));
            if (r.hasNonNull("pageNo")) {
                m.setPageNo(r.get("pageNo").asInt());
            }
            m.setSnippet(r.path("snippet").asText(null));
            m.setCreatedAt(instant(r, "createdAt", now));
            list.add(m);
        }
        metadataDao.saveAll(list);
    }

    private void seedFeedback(JsonNode rows, Instant now) {
        List<com.wikiagent.infrastructure.persistence.KbFeedbackEntity> list = new ArrayList<>();
        for (JsonNode r : rows) {
            var f = new com.wikiagent.infrastructure.persistence.KbFeedbackEntity();
            f.setUserId(r.path("userId").asText("eval-user"));
            f.setSessionId(r.path("sessionId").asText("eval-session"));
            f.setConversationId(r.path("conversationId").asText("eval-conv"));
            f.setChunkId(r.path("chunkId").asText());
            f.setFeedbackType(r.path("feedbackType").asText("USEFUL"));
            f.setCreatedAt(instant(r, "createdAt", now));
            list.add(f);
        }
        feedbackDao.saveAll(list);
    }

    private void seedFields(JsonNode rows, Instant now) {
        List<ExtractedFieldEntity> list = new ArrayList<>();
        for (JsonNode r : rows) {
            ExtractedFieldEntity f = new ExtractedFieldEntity();
            f.setDocId(r.path("docId").asText());
            f.setFieldKey(r.path("fieldKey").asText());
            f.setFieldLabel(r.path("fieldLabel").asText(null));
            f.setValueText(r.path("valueText").asText(null));
            f.setValueType(r.path("valueType").asText("STRING"));
            f.setConfidence(r.path("confidence").asDouble(1.0));
            f.setSource(r.path("source").asText("MODEL"));
            f.setSchemaKey(r.path("schemaKey").asText(null));
            f.setSchemaVersion(r.path("schemaVersion").asText(null));
            f.setValid(r.path("valid").asBoolean(true));
            f.setReviewRequired(r.path("reviewRequired").asBoolean(false));
            f.setVersionNo(r.path("versionNo").asInt(1));
            if (r.hasNonNull("pageNo")) {
                f.setPageNo(r.get("pageNo").asInt());
            }
            f.setSnippet(r.path("snippet").asText(null));
            Instant created = instant(r, "createdAt", now);
            f.setCreatedAt(created);
            f.setUpdatedAt(instant(r, "updatedAt", created));
            list.add(f);
        }
        fieldRepo.saveAll(list);
    }

    private void seedReviewCases(JsonNode rows, Instant now) {
        List<ReviewCaseEntity> list = new ArrayList<>();
        for (JsonNode r : rows) {
            ReviewCaseEntity c = new ReviewCaseEntity();
            c.setCaseType(r.path("caseType").asText());
            c.setDocId(r.path("docId").asText(null));
            c.setFieldKey(r.path("fieldKey").asText(null));
            c.setSource(r.path("source").asText(null));
            if (r.hasNonNull("confidence")) {
                c.setConfidence(r.get("confidence").asDouble());
            }
            c.setStatus(r.path("status").asText());
            c.setRuleCode(r.path("ruleCode").asText(null));
            c.setResolvedBy(r.path("resolvedBy").asText(null));
            Instant created = instant(r, "createdAt", now);
            c.setCreatedAt(created);
            c.setResolvedAt(instant(r, "resolvedAt", null));
            list.add(c);
        }
        reviewCaseRepo.saveAll(list);
    }

    private void seedModelCalls(JsonNode rows, Instant now) {
        List<ModelCallLogEntity> list = new ArrayList<>();
        for (JsonNode r : rows) {
            ModelCallLogEntity c = new ModelCallLogEntity();
            c.setPurpose(r.path("purpose").asText());
            c.setProvider(r.path("provider").asText("noop"));
            c.setModel(r.path("model").asText());
            if (r.hasNonNull("tokensIn")) {
                c.setTokensIn(r.get("tokensIn").asInt());
            }
            if (r.hasNonNull("tokensOut")) {
                c.setTokensOut(r.get("tokensOut").asInt());
            }
            if (r.hasNonNull("costEstimate")) {
                c.setCostEstimate(r.get("costEstimate").asDouble());
            }
            if (r.hasNonNull("latencyMs")) {
                c.setLatencyMs(r.get("latencyMs").asLong());
            }
            c.setStatus(r.path("status").asText("OK"));
            c.setCreatedAt(instant(r, "createdAt", now));
            list.add(c);
        }
        modelCallDao.saveAll(list);
    }

    /** "NOW" 哨兵（或缺失/null 字段）→ fallback；ISO 字符串按 Instant 解析。 */
    private static Instant instant(JsonNode node, String field, Instant fallback) {
        if (!node.hasNonNull(field)) {
            return fallback;
        }
        String raw = node.get(field).asText();
        if (NOW.equals(raw)) {
            return Instant.now();
        }
        return Instant.parse(raw);
    }
}
