package com.wikiagent.application.rule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wikiagent.application.lineage.ProvenanceService;
import com.wikiagent.application.task.HumanTaskService;
import com.wikiagent.domain.extract.ExtractedFieldValue;
import com.wikiagent.domain.extract.FieldValueType;
import com.wikiagent.domain.lineage.EdgeType;
import com.wikiagent.domain.rule.ReviewCase;
import com.wikiagent.domain.task.ActorType;
import com.wikiagent.domain.task.HumanTask;
import com.wikiagent.domain.task.HumanTaskKind;
import com.wikiagent.domain.task.HumanTaskStatus;
import com.wikiagent.domain.task.TaskEvent;
import com.wikiagent.domain.task.TaskEventType;
import com.wikiagent.domain.task.ports.HumanTaskRepositoryPort;
import com.wikiagent.domain.task.ports.TaskEventRepositoryPort;
import com.wikiagent.dto.ConflictException;
import com.wikiagent.dto.NotFoundException;
import com.wikiagent.entity.rule.ReviewCaseEntity;
import com.wikiagent.repo.rule.ReviewCaseRepo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 复核案件服务：低置信/材料差异案件的创建与处置（通过/驳回/编辑）。
 */
@Service
public class ReviewCaseService {

    private final ReviewCaseRepo repo;
    private final ProvenanceService provenance;
    private final ObjectMapper mapper;
    private final ObjectProvider<TaskEventRepositoryPort> eventRepoProvider;
    private final ObjectProvider<HumanTaskRepositoryPort> humanTaskRepoProvider;
    private final ObjectProvider<HumanTaskService> humanTaskServiceProvider;
    /** #7 复核闸门行锁（JPA 原生 FOR UPDATE），无 JPA 环境时退化为旧逻辑。 */
    private final ObjectProvider<EntityManager> entityManagerProvider;

    public ReviewCaseService(ReviewCaseRepo repo,
                             ProvenanceService provenance,
                             ObjectMapper mapper,
                             ObjectProvider<TaskEventRepositoryPort> eventRepoProvider,
                             ObjectProvider<HumanTaskRepositoryPort> humanTaskRepoProvider,
                             ObjectProvider<HumanTaskService> humanTaskServiceProvider,
                             ObjectProvider<EntityManager> entityManagerProvider) {
        this.repo = repo;
        this.provenance = provenance;
        this.mapper = mapper;
        this.eventRepoProvider = eventRepoProvider;
        this.humanTaskRepoProvider = humanTaskRepoProvider;
        this.humanTaskServiceProvider = humanTaskServiceProvider;
        this.entityManagerProvider = entityManagerProvider;
    }

    @Transactional
    public ReviewCase createLowConfidence(String docId, Integer versionNo, String fieldKey,
                                          String source, Double confidence, String reason,
                                          String taskId) {
        ReviewCaseEntity e = new ReviewCaseEntity();
        e.setCaseType(ReviewCase.ReviewCaseType.LOW_CONFIDENCE.name());
        e.setDocId(docId);
        e.setVersionNo(versionNo);
        e.setFieldKey(fieldKey);
        e.setSource(source);
        e.setConfidence(confidence);
        e.setDiffJson(reason == null ? null : "{\"reason\":\"" + escape(reason) + "\"}");
        e.setStatus(ReviewCase.ReviewCaseStatus.OPEN.name());
        e.setTaskId(taskId);
        e.setCreatedAt(Instant.now());
        return toDomain(repo.save(e));
    }

    @Transactional
    public ReviewCase createMaterialDiff(String docId, String ruleCode, Integer ruleVersion,
                                         Long computationId, String diffJson) {
        ReviewCaseEntity e = new ReviewCaseEntity();
        e.setCaseType(ReviewCase.ReviewCaseType.MATERIAL_DIFF.name());
        e.setDocId(docId);
        e.setRuleCode(ruleCode);
        e.setRuleVersion(ruleVersion);
        e.setComputationId(computationId);
        e.setDiffJson(diffJson);
        e.setStatus(ReviewCase.ReviewCaseStatus.OPEN.name());
        e.setCreatedAt(Instant.now());
        return toDomain(repo.save(e));
    }

    public List<ReviewCase> list(String status, String docId) {
        if (status != null && !status.isBlank()) {
            return repo.findByStatusOrderByCreatedAtDesc(status).stream().map(this::toDomain).toList();
        }
        if (docId != null && !docId.isBlank()) {
            return repo.findByDocIdOrderByCreatedAtDesc(docId).stream().map(this::toDomain).toList();
        }
        return repo.findAll().stream().map(this::toDomain).toList();
    }

    public ReviewCase get(Long id) {
        return repo.findById(id).map(this::toDomain)
                .orElseThrow(() -> new NotFoundException("复核案件不存在: " + id));
    }

    /**
     * 处置案件：APPROVE / REJECT / EDIT。
     * EDIT 对每个 editedFields 条目：upsertField(reviewed=true) + EDITED 边。
     * 三动作均写 resolution_json、REVIEWED 边、task_event；若有关联 REVIEW 人工任务则恢复任务。
     */
    @Transactional
    public ReviewCase dispose(Long caseId, ReviewCase.ReviewAction action,
                              Map<String, String> editedFields, String userId) {
        ReviewCaseEntity e = repo.findById(caseId)
                .orElseThrow(() -> new NotFoundException("复核案件不存在: " + caseId));
        if (!e.getStatus().equals(ReviewCase.ReviewCaseStatus.OPEN.name())) {
            throw new ConflictException("案件已处置: " + e.getStatus());
        }
        Instant now = Instant.now();

        // 1) EDIT：字段级编辑落库 + EDITED 边
        if (action == ReviewCase.ReviewAction.EDIT && editedFields != null && !editedFields.isEmpty()) {
            int docVersionNo = resolveDocVersionNo(e);
            for (Map.Entry<String, String> entry : editedFields.entrySet()) {
                String fieldKey = entry.getKey();
                String newValue = entry.getValue();
                String oldValue = currentFieldValue(e.getDocId(), fieldKey);
                ExtractedFieldValue value = new ExtractedFieldValue(fieldKey, newValue,
                        FieldValueType.STRING, 1.0, com.wikiagent.domain.extract.FieldSource.HUMAN,
                        true, List.of(), List.of());
                provenance.upsertField(e.getDocId(), docVersionNo, value,
                        e.getRuleCode() == null ? "review" : "rule:" + e.getRuleCode(),
                        e.getRuleVersion() == null ? null : String.valueOf(e.getRuleVersion()),
                        fieldKey, true);
                provenance.addEdge(e.getDocId(), docVersionNo, fieldKey, "FIELD",
                        fieldKey, "FIELD", EdgeType.EDITED,
                        "人工编辑: " + oldValue + "→" + newValue);
            }
        }

        // 2) 状态迁移
        e.setStatus(switch (action) {
            case APPROVE -> ReviewCase.ReviewCaseStatus.APPROVED.name();
            case REJECT -> ReviewCase.ReviewCaseStatus.REJECTED.name();
            case EDIT -> ReviewCase.ReviewCaseStatus.EDITED.name();
        });
        e.setResolvedBy(userId);
        e.setResolvedAt(now);

        // 3) resolution_json
        ObjectNode resolution = mapper.createObjectNode()
                .put("action", action.name())
                .put("resolvedAt", now.toString())
                .put("resolvedBy", userId);
        if (editedFields != null && !editedFields.isEmpty()) {
            resolution.set("editedFields", mapper.valueToTree(editedFields));
        }
        e.setResolutionJson(resolution.toString());
        ReviewCase saved = toDomain(repo.save(e));

        // 4) REVIEWED 血缘边（docId 非空才写）
        if (e.getDocId() != null) {
            int docVersionNo = resolveDocVersionNo(e);
            provenance.addEdge(e.getDocId(), docVersionNo,
                    String.valueOf(e.getId()), "REVIEW_CASE", e.getDocId(), "DOCUMENT",
                    EdgeType.REVIEWED, action.name());
        }

        // 5) task_event 留痕（taskId 非空）
        if (e.getTaskId() != null) {
            TaskEventRepositoryPort eventRepo = eventRepoProvider.getIfAvailable();
            if (eventRepo != null) {
                ObjectNode detail = mapper.createObjectNode()
                        .put("caseId", e.getId())
                        .put("action", action.name())
                        .put("resolvedBy", userId);
                eventRepo.append(new TaskEvent(e.getTaskId(), TaskEventType.HUMAN_RESOLVE,
                        ActorType.USER, userId, detail, now));
            }
        }

        // 6) 恢复关联 REVIEW 人工任务（OPEN/CLAIMED）
        if (e.getTaskId() != null) {
            resumeReviewHumanTask(e, action, editedFields, userId);
        }
        return saved;
    }

    /**
     * 恢复关联 REVIEW 人工任务（#7 行锁串行化）。
     * <p>
     * 两 reviewer 并发处置同任务最后两个 OPEN 案件时，修复前两边的"仍有 OPEN"判断
     * 可能都基于旧快照成立，导致无人 resolve、任务永久 WAITING_HUMAN。现在：
     * <ol>
     *   <li>先以 SELECT … FOR UPDATE 锁定该任务 OPEN/CLAIMED 的 REVIEW 人工任务行
     *       （当前读；无行则直接返回）。查询显式 COMMIT 刷新模式——必须先抢 human_task
     *       锁再 flush 本案件更新，否则两事务先各锁 review_case 再争 human_task 会死锁；</li>
     *   <li>锁内 flush 本案件处置，再以 FOR UPDATE 当前读计数剩余 OPEN 案件
     *       （InnoDB REPEATABLE READ 的普通一致性读会沿用事务早期快照，锁等待后仍读到旧值，
     *       而 FOR UPDATE 恒为当前读；H2 READ_COMMITTED 下行锁等待同样完成串行化）；</li>
     *   <li>剩余 OPEN=0 才 resolve。行锁使两个 dispose 严格排队，resolve 至多被调用一次；
     *       HumanTaskService 侧条件更新（status in OPEN/CLAIMED）为第二道防线。</li>
     * </ol>
     *
     * @return 是否存在可恢复的 OPEN/CLAIMED REVIEW 人工任务（#9 事件去重据此判断）
     */
    private boolean resumeReviewHumanTask(ReviewCaseEntity e, ReviewCase.ReviewAction action,
                                          Map<String, String> editedFields, String userId) {
        HumanTaskRepositoryPort humanTaskRepo = humanTaskRepoProvider.getIfAvailable();
        HumanTaskService humanTaskService = humanTaskServiceProvider.getIfAvailable();
        EntityManager em = entityManagerProvider.getIfAvailable();
        if (humanTaskRepo == null || humanTaskService == null || em == null) {
            return false;
        }
        List<Long> lockedHumanTaskIds = lockReviewHumanTaskRows(em, e.getTaskId());
        if (lockedHumanTaskIds.isEmpty()) {
            return false;
        }
        // 本案件状态迁移/血缘边先落库，再做当前读计数
        em.flush();
        List<?> openCaseIds = em.createNativeQuery("""
                        SELECT id FROM review_case
                        WHERE task_id = :taskId AND status = 'OPEN'
                        FOR UPDATE
                        """)
                .setParameter("taskId", e.getTaskId())
                .setFlushMode(FlushModeType.COMMIT)
                .getResultList();
        if (!openCaseIds.isEmpty()) {
            // 同任务仍有 OPEN 案件：本次只留痕不恢复，等最后一个案件处置后再 resolve
            return true;
        }
        for (Long htId : lockedHumanTaskIds) {
            HumanTask ht = humanTaskRepo.findById(htId).orElse(null);
            if (ht == null || ht.kind() != HumanTaskKind.REVIEW
                    || (ht.status() != HumanTaskStatus.OPEN && ht.status() != HumanTaskStatus.CLAIMED)) {
                continue;
            }
            ObjectNode formValue = mapper.createObjectNode()
                    .put("action", action.name())
                    .put("caseId", e.getId())
                    .put("resolvedBy", userId);
            if (editedFields != null && !editedFields.isEmpty()) {
                formValue.set("editedFields", mapper.valueToTree(editedFields));
            }
            humanTaskService.resolve(htId, userId, HumanTaskKind.REVIEW, formValue, null);
            // 更新案件关联的 human_task_id
            e.setHumanTaskId(htId);
            repo.save(e);
            break;
        }
        return true;
    }

    /** 锁定任务的 OPEN/CLAIMED REVIEW 人工任务行；COMMIT 刷新模式确保先拿锁后 flush。 */
    @SuppressWarnings("unchecked")
    private static List<Long> lockReviewHumanTaskRows(EntityManager em, String taskId) {
        return em.createNativeQuery("""
                        SELECT id FROM human_task
                        WHERE task_id = :taskId AND kind = 'REVIEW'
                          AND status IN ('OPEN', 'CLAIMED')
                        FOR UPDATE
                        """)
                .setParameter("taskId", taskId)
                .setFlushMode(FlushModeType.COMMIT)
                .getResultList();
    }

    private int resolveDocVersionNo(ReviewCaseEntity e) {
        if (e.getDocId() == null) {
            return 1;
        }
        var latest = provenance.latestVersion(e.getDocId());
        return latest == null ? 1 : latest.versionNo();
    }

    private String currentFieldValue(String docId, String fieldKey) {
        if (docId == null) {
            return null;
        }
        try {
            return provenance.fieldHistory(docId, fieldKey).stream()
                    .reduce((a, b) -> b).map(fv -> fv.valueText()).orElse(null);
        } catch (Exception ex) {
            return null;
        }
    }

    private ReviewCase toDomain(ReviewCaseEntity e) {
        return new ReviewCase(e.getId(),
                ReviewCase.ReviewCaseType.valueOf(e.getCaseType()),
                e.getDocId(), e.getVersionNo(), e.getFieldKey(), e.getDiffJson(), e.getSource(),
                e.getConfidence(), ReviewCase.ReviewCaseStatus.valueOf(e.getStatus()),
                e.getHumanTaskId(), e.getTaskId(), e.getRuleCode(), e.getRuleVersion(),
                e.getComputationId(), e.getResolutionJson(), e.getResolvedBy(),
                e.getCreatedAt(), e.getResolvedAt());
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
