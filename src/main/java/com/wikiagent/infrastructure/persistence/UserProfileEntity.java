package com.wikiagent.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * v1-v2 §5 用户档案 JPA 实体（对应 V1__init.sql 中 user_profile 表）。
 * <p>
 * 字段与 SQL 列保持一一对应：
 * <ul>
 *   <li>id → user_id (VARCHAR(64) PK)</li>
 *   <li>displayName → display_name</li>
 *   <li>preferredLanguage → preferred_language</li>
 *   <li>businessIdentity → business_identity</li>
 *   <li>overrides → overrides (TEXT)</li>
 *   <li>assignedDomains → assigned_domains (TEXT)</li>
 *   <li>createdAt → created_at</li>
 *   <li>updatedAt → updated_at</li>
 * </ul>
 */
@Entity
@Table(name = "user_profile")
public class UserProfileEntity {

    @Id
    @Column(name = "user_id", length = 64, nullable = false)
    private String id;

    @Column(name = "display_name", length = 128)
    private String displayName;

    @Column(name = "preferred_language", length = 16)
    private String preferredLanguage = "zh-CN";

    @Column(name = "business_identity", length = 32)
    private String businessIdentity = "business";

    @Column(name = "overrides", columnDefinition = "TEXT")
    private String overrides;

    @Column(name = "assigned_domains", columnDefinition = "TEXT")
    private String assignedDomains;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getPreferredLanguage() { return preferredLanguage; }
    public void setPreferredLanguage(String preferredLanguage) { this.preferredLanguage = preferredLanguage; }

    public String getBusinessIdentity() { return businessIdentity; }
    public void setBusinessIdentity(String businessIdentity) { this.businessIdentity = businessIdentity; }

    public String getOverrides() { return overrides; }
    public void setOverrides(String overrides) { this.overrides = overrides; }

    public String getAssignedDomains() { return assignedDomains; }
    public void setAssignedDomains(String assignedDomains) { this.assignedDomains = assignedDomains; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
