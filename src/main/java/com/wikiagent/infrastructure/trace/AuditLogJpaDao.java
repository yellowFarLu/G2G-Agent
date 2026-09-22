package com.wikiagent.infrastructure.trace;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * v1-v2 §10 安全护栏审计日志 Spring Data JPA Repository。
 * <p>
 * 提供 audit_log 表的基础 CRUD 操作。
 */
public interface AuditLogJpaDao extends JpaRepository<AuditLogEntity, Long> {
}
