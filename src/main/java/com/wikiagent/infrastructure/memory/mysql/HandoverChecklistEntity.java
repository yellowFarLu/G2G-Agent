package com.wikiagent.infrastructure.memory.mysql;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * handover_checklist 行实体（V9，规格 2.5）：每 user+session 一份清单，original_request 不可变。
 */
@Entity
@Table(name = "handover_checklist")
public class HandoverChecklistEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "original_request", nullable = false, columnDefinition = "TEXT")
    private String originalRequest;

    @Column(name = "plan_json", columnDefinition = "TEXT")
    private String planJson;

    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getOriginalRequest() { return originalRequest; }
    public void setOriginalRequest(String originalRequest) { this.originalRequest = originalRequest; }
    public String getPlanJson() { return planJson; }
    public void setPlanJson(String planJson) { this.planJson = planJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
