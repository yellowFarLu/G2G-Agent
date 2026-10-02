package com.wikiagent.infrastructure.persistence.llm;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 模型调用日志 JPA DAO。
 */
public interface ModelCallLogJpaDao extends JpaRepository<ModelCallLogEntity, Long> {

    List<ModelCallLogEntity> findByTraceId(String traceId);

    List<ModelCallLogEntity> findBySessionId(String sessionId);
}
