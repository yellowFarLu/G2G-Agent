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

    public ReviewCaseService(ReviewCaseRepo repo,
                             ProvenanceService provenance,
                             ObjectMapper mapper,
                             ObjectProvider<TaskEventRepositoryPort> eventRepoProvider,
                             ObjectProvider<HumanTaskRepositoryPort> humanTaskRepoProvider,
                             ObjectProvider<HumanTaskService> humanTaskServiceProvider) {
        this.repo = repo;
        this.provenance = provenance;
        this.mapper = mapper;
        this.eventRepoProvider = eventRepoProvider;
        this.humanTaskRepoProvider = humanTaskRepoProvider;
        this.humanTaskServiceProvider = humanTaskServiceProvider;
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

    private void resumeReviewHumanTask(ReviewCaseEntity e, ReviewCase.ReviewAction action,
                                       Map<String, String> editedFields, String userId) {
        HumanTaskRepositoryPort humanTaskRepo = humanTaskRepoProvider.getIfAvailable();
        HumanTaskService humanTaskService = humanTaskServiceProvider.getIfAvailable();
        if (humanTaskRepo == null || humanTaskService == null) {
            return;
        }
        List<HumanTask> tasks = humanTaskRepo.findByTaskId(e.getTaskId());
        // 复核闸门：同一任务仍有 OPEN 案件时，本次处置只留痕不恢复流水线，
        // 待最后一个案件处置后再 RESUME，避免未复核字段随文档入库。
        boolean stillOpen = repo.findByTaskIdAndStatus(e.getTaskId(),
                ReviewCase.ReviewCaseStatus.OPEN.name()).stream()
                .anyMatch(c -> !c.getId().equals(e.getId()));
        if (stillOpen) {
            return;
        }
        for (HumanTask ht : tasks) {
            if (ht.kind() == HumanTaskKind.REVIEW
                    && (ht.status() == HumanTaskStatus.OPEN || ht.status() == HumanTaskStatus.CLAIMED)) {
                ObjectNode formValue = mapper.createObjectNode()
                        .put("action", action.name())
                        .put("caseId", e.getId());
                if (editedFields != null && !editedFields.isEmpty()) {
                    formValue.set("editedFields", mapper.valueToTree(editedFields));
                }
                humanTaskService.resolve(ht.id(), userId, HumanTaskKind.REVIEW, formValue, null);
                // 更新案件关联的 human_task_id
                e.setHumanTaskId(ht.id());
                repo.save(e);
                break;
            }
        }
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
