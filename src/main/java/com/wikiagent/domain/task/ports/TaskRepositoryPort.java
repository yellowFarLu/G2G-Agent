package com.wikiagent.domain.task.ports;

import com.wikiagent.domain.task.TaskInstance;

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

    void updateHeartbeat(String taskId, Instant at);
}
