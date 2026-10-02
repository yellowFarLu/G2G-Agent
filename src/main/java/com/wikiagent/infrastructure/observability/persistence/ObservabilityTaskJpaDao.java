package com.wikiagent.infrastructure.observability.persistence;

import com.wikiagent.infrastructure.task.jpa.TaskInstanceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 子项目 I（AC-I2/I3）可观测只读 DAO：复用 {@link TaskInstanceEntity}，
 * 与任务写 DAO 分离（不编辑既有持久化类）。Spring Data 派生查询，H2/MySQL 均兼容。
 */
public interface ObservabilityTaskJpaDao extends JpaRepository<TaskInstanceEntity, Long> {

    long countByStatus(String status);

    long countByStatusAndTenantId(String status, String tenantId);
}
