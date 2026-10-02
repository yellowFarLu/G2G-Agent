package com.wikiagent.infrastructure.observability.persistence;

import com.wikiagent.domain.observability.TaskMetricsQueryPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link TaskMetricsQueryPort} 的 JPA 只读适配器。
 * 任务框架关闭时不装配（与 JpaTaskRepository 同条件），避免无谓 Bean 失败。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.task.enabled", havingValue = "true", matchIfMissing = true)
public class JpaTaskMetricsQueryAdapter implements TaskMetricsQueryPort {

    private final ObservabilityTaskJpaDao dao;

    public JpaTaskMetricsQueryAdapter(ObservabilityTaskJpaDao dao) {
        this.dao = dao;
    }

    @Override
    @Transactional(readOnly = true)
    public long countByStatus(String status) {
        return dao.countByStatus(status);
    }

    @Override
    @Transactional(readOnly = true)
    public long countRunningByTenant(String tenantId) {
        return dao.countByStatusAndTenantId("RUNNING", tenantId);
    }
}
