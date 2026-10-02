package com.wikiagent.domain.observability;

/**
 * 子项目 I（AC-I2/I3）任务侧只读观测端口（domain 层）。
 * <p>
 * 仅暴露可观测/背压判定需要的 count 查询，与任务写仓储端口分离，
 * 任务框架关闭（wikiagent.task.enabled=false）时适配器不装配，消费方须 ObjectProvider 容错。
 */
public interface TaskMetricsQueryPort {

    /** 按状态统计任务数（PENDING/RUNNING/WAITING_HUMAN/FAILED ...）。 */
    long countByStatus(String status);

    /**
     * 同租户 RUNNING 任务数（队列背压：对接 wikiagent.task.tenant-max-concurrent）。
     * tenantId 为 null 时按未归属租户统计。
     */
    long countRunningByTenant(String tenantId);
}
