package com.wikiagent.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * v5 §19 安全网关审计日志 JPA DAO。
 */
public interface GatewayAuditLogJpaDao extends JpaRepository<GatewayAuditLogEntity, Long> {

    List<GatewayAuditLogEntity> findBySessionId(String sessionId);

    List<GatewayAuditLogEntity> findByDetectorNameAndDirection(String detectorName, String direction);

    List<GatewayAuditLogEntity> findByDetectionResult(String detectionResult);
}
