package com.wikiagent.infrastructure.memory.mysql;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * handover_abandoned_path 行实体（V9，规格 2.5）。
 */
@Entity
@Table(name = "handover_abandoned_path")
public class HandoverAbandonedPathEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "checklist_id", nullable = false)
    private Long checklistId;

    @Column(name = "node_id", nullable = false, length = 64)
    private String nodeId;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getChecklistId() { return checklistId; }
    public void setChecklistId(Long checklistId) { this.checklistId = checklistId; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
