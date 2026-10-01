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

    /** CAS 抢租约：仅 PENDING/DISPATCH 且无主或已过期才成功（规格 3.1 ①②）。 */
    @Modifying(flushAutomatically = true)
    @Query("""
            update TaskInstanceEntity t
            set t.leaseOwner = :workerId, t.leaseExpireAt = :expireAt, t.updatedAt = :now
            where t.taskId = :taskId
              and t.status in ('PENDING', 'DISPATCH')
              and (t.leaseOwner is null or t.leaseExpireAt is null or t.leaseExpireAt < :now)
            """)
    int casLease(@Param("taskId") String taskId,
                 @Param("workerId") String workerId,
                 @Param("expireAt") LocalDateTime expireAt,
                 @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true)
    @Query("update TaskInstanceEntity t set t.heartbeatAt = :at, t.updatedAt = :at where t.taskId = :taskId")
    int updateHeartbeat(@Param("taskId") String taskId, @Param("at") LocalDateTime at);

    @Modifying(flushAutomatically = true)
    @Query("update TaskInstanceEntity t set t.enqueueAt = :at, t.updatedAt = :at where t.taskId = :taskId")
    int markEnqueued(@Param("taskId") String taskId, @Param("at") LocalDateTime at);

    /** 控制痕迹定向更新（suspend/resume）：仅写 suspend_reason/control_version，不触状态与租约。 */
    @Modifying(flushAutomatically = true)
    @Query("""
            update TaskInstanceEntity t
            set t.suspendReason = :reason, t.controlVersion = :version, t.updatedAt = :now
            where t.taskId = :taskId and t.status not in ('COMPLETED', 'FAILED', 'CANCELLED')
            """)
    int updateControlTrace(@Param("taskId") String taskId,
                           @Param("reason") String reason,
                           @Param("version") int version,
                           @Param("now") LocalDateTime now);

    /** 取消 CAS：RUNNING/SUSPENDED → CANCELING（原子，杜绝终态复活）。 */
    @Modifying(flushAutomatically = true)
    @Query("""
            update TaskInstanceEntity t
            set t.status = 'CANCELING', t.controlVersion = :version, t.updatedAt = :now
            where t.taskId = :taskId and t.status in ('RUNNING', 'SUSPENDED')
            """)
    int casCanceling(@Param("taskId") String taskId,
                     @Param("version") int version,
                     @Param("now") LocalDateTime now);

    /** 恢复 CAS：SUSPENDED → PENDING 并清租约与暂停痕迹。 */
    @Modifying(flushAutomatically = true)
    @Query("""
            update TaskInstanceEntity t
            set t.status = 'PENDING', t.suspendReason = null, t.controlVersion = :version,
                t.leaseOwner = null, t.leaseExpireAt = null, t.updatedAt = :now
            where t.taskId = :taskId and t.status = 'SUSPENDED'
            """)
    int casResume(@Param("taskId") String taskId,
                  @Param("version") int version,
                  @Param("now") LocalDateTime now);

    long countByStatusAndTenantId(String status, String tenantId);

    /** 列表查询：status/submittedBy 可空不过滤，createdAt 倒序。 */
    @Query("""
            select t from TaskInstanceEntity t
            where (:status is null or t.status = :status)
              and (:submitter is null or t.submittedBy = :submitter)
            order by t.createdAt desc
            """)
    List<TaskInstanceEntity> search(@Param("status") String status,
                                    @Param("submitter") String submitter,
                                    @Param("limit") int limit);
}
