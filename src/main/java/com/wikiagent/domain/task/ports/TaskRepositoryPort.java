package com.wikiagent.domain.task.ports;

import com.wikiagent.domain.task.TaskInstance;
import com.wikiagent.domain.task.TaskStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 任务实例仓储端口（MySQL 权威真相源）。
 */
public interface TaskRepositoryPort {

    Optional<TaskInstance> findByTaskId(String taskId);

    Optional<TaskInstance> findByBizKey(String bizKey);

    TaskInstance save(TaskInstance t);

    /** 可投递任务：PENDING 且 next_run_at<=now，跳过 enqueue_at 距今不足 stuckAge 的（outbox 未确认窗口）。 */
    List<TaskInstance> findDispatchable(Duration stuckAge, int limit);

    /** 租约过期仍在 RUNNING 的任务（崩溃恢复候选）。 */
    List<TaskInstance> findExpiredLeases(Instant now, int limit);

    /** CAS 抢租约：仅当无主或租约过期时置 owner/expireAt，返回是否成功。 */
    boolean casLease(String taskId, String workerId, Instant expireAt);

    /** 心跳续租（DB 权威）：仅当租约仍属于 owner 且任务仍 RUNNING 时刷新 lease_expire_at/heartbeat_at；
     * 返回 false 表示租约易主或任务状态已变（调用方必须让位，不再写任何任务状态）。 */
    boolean renewLease(String taskId, String workerId, Instant expireAt, Instant at);

    /** 投递成功后记账（outbox 确认时间戳），补偿扫描据此跳过未滞留窗口。 */
    void markEnqueued(String taskId, Instant at);

    /** 控制痕迹定向更新（suspend/resume）：仅写 suspend_reason/control_version，不触状态与租约；终态任务不生效。 */
    void updateControlTrace(String taskId, String suspendReason, int controlVersion);

    /** 取消 CAS：RUNNING/SUSPENDED → CANCELING（原子），返回受影响行数（0=状态已变化）。 */
    int casCanceling(String taskId, int controlVersion);

    /** 恢复 CAS：SUSPENDED → PENDING 并清租约与暂停痕迹，返回受影响行数。 */
    int casResume(String taskId, int controlVersion);

    /** 同租户 RUNNING 任务数（租户并发上限判定用）。 */
    long countRunningByTenant(String tenantId);

    /** 列表查询：status/submittedBy 均可空（null 不过滤），createdAt 倒序。 */
    List<TaskInstance> search(TaskStatus status, String submittedBy, int limit);
}
