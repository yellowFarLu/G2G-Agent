package com.wikiagent.infrastructure.tool;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * F1：tool_permission 表数据访问。
 */
public interface ToolPermissionJpaDao extends JpaRepository<ToolPermissionEntity, Long> {

    Optional<ToolPermissionEntity> findByToolName(String toolName);
}
