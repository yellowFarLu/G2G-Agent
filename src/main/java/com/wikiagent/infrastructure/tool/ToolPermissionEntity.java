package com.wikiagent.infrastructure.tool;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * F1：tool_permission 表 JPA 实体（V15）。
 */
@Entity
@Table(name = "tool_permission")
public class ToolPermissionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tool_name", nullable = false, length = 64, unique = true)
    private String toolName;

    @Column(name = "required_role", length = 64)
    private String requiredRole;

    @Column(name = "required_scope", length = 64)
    private String requiredScope;

    @Column(name = "requires_approval", nullable = false)
    private boolean requiresApproval;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public String getRequiredRole() { return requiredRole; }
    public void setRequiredRole(String requiredRole) { this.requiredRole = requiredRole; }

    public String getRequiredScope() { return requiredScope; }
    public void setRequiredScope(String requiredScope) { this.requiredScope = requiredScope; }

    public boolean isRequiresApproval() { return requiresApproval; }
    public void setRequiresApproval(boolean requiresApproval) { this.requiresApproval = requiresApproval; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
