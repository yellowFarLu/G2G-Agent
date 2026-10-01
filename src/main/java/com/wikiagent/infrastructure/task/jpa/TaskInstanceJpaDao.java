package com.wikiagent.infrastructure.task.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * task_instance JPA DAO。
 */
public interface TaskInstanceJpaDao extends JpaRepository<TaskInstanceEntity, Long> {

    Optional<TaskInstanceEntity> findByTaskId(String taskId);

    Optional<TaskInstanceEntity> findByBizKey(String bizKey);

    List<TaskInstanceEntity> findByStatus(String status);

    /** 可投递：PENDING 且到点，且 enqueue_at 为空或早于 now-stuckAge（outbox 未确认窗口外）。 */
    @Query("""
            select t from TaskInstanceEntity t
            where t.status = 'PENDING' and t.nextRunAt <= :now
              and (t.enqueueAt is null or t.enqueueAt < :stuckBefore)
            order by t.nextRunAt asc
            """)
    List<TaskInstanceEntity> findDispatchable(@Param("now") LocalDateTime now,
                                              @Param("stuckBefore") LocalDateTime stuckBefore,
                                              @Param("limit") int limit);

    /** 租约过期的 RUNNING 任务（崩溃恢复候选）。 */
    @Query("""
            select t from TaskInstanceEntity t
            where t.status = 'RUNNING' and t.leaseExpireAt is not null and t.leaseExpireAt < :now
            order by t.leaseExpireAt asc
            """)
    List<TaskInstanceEntity> findExpiredLeases(@Param("now") LocalDateTime now,
                                               @Param("limit") int limit);

    /** CAS 抢租约：无主或已过期才成功。 */
    @Modifying(flushAutomatically = true)
    @Query("""
            update TaskInstanceEntity t
            set t.leaseOwner = :workerId, t.leaseExpireAt = :expireAt, t.updatedAt = :now
            where t.taskId = :taskId
              and (t.leaseOwner is null or t.leaseExpireAt is null or t.leaseExpireAt < :now)
            """)
    int casLease(@Param("taskId") String taskId,
                 @Param("workerId") String workerId,
                 @Param("expireAt") LocalDateTime expireAt,
                 @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true)
    @Query("update TaskInstanceEntity t set t.heartbeatAt = :at, t.updatedAt = :at where t.taskId = :taskId")
    int updateHeartbeat(@Param("taskId") String taskId, @Param("at") LocalDateTime at);
}
