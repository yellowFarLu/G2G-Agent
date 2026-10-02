package com.wikiagent.entity.rule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "review_case")
public class ReviewCaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_type", nullable = false, length = 32)
    private String caseType;

    @Column(name = "doc_id", length = 64)
    private String docId;

    @Column(name = "version_no")
    private Integer versionNo;

    @Column(name = "field_key", length = 128)
    private String fieldKey;

    @Column(name = "diff_json", columnDefinition = "TEXT")
    private String diffJson;

    @Column(name = "source", length = 16)
    private String source;

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "human_task_id")
    private Long humanTaskId;

    @Column(name = "task_id", length = 64)
    private String taskId;

    @Column(name = "rule_code", length = 128)
    private String ruleCode;

    @Column(name = "rule_version")
    private Integer ruleVersion;

    @Column(name = "computation_id")
    private Long computationId;

    @Column(name = "resolution_json", columnDefinition = "TEXT")
    private String resolutionJson;

    @Column(name = "resolved_by", length = 64)
    private String resolvedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCaseType() { return caseType; }
    public void setCaseType(String caseType) { this.caseType = caseType; }
    public String getDocId() { return docId; }
    public void setDocId(String docId) { this.docId = docId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getFieldKey() { return fieldKey; }
    public void setFieldKey(String fieldKey) { this.fieldKey = fieldKey; }
    public String getDiffJson() { return diffJson; }
    public void setDiffJson(String diffJson) { this.diffJson = diffJson; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getHumanTaskId() { return humanTaskId; }
    public void setHumanTaskId(Long humanTaskId) { this.humanTaskId = humanTaskId; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getRuleCode() { return ruleCode; }
    public void setRuleCode(String ruleCode) { this.ruleCode = ruleCode; }
    public Integer getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(Integer ruleVersion) { this.ruleVersion = ruleVersion; }
    public Long getComputationId() { return computationId; }
    public void setComputationId(Long computationId) { this.computationId = computationId; }
    public String getResolutionJson() { return resolutionJson; }
    public void setResolutionJson(String resolutionJson) { this.resolutionJson = resolutionJson; }
    public String getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(String resolvedBy) { this.resolvedBy = resolvedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
}
