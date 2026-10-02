package com.wikiagent.infrastructure.task.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * task_step JPA DAO。
 */
public interface TaskStepJpaDao extends JpaRepository<TaskStepEntity, Long> {

    Optional<TaskStepEntity> findByTaskIdAndStepNo(String taskId, int stepNo);

    boolean existsByTaskIdAndStepNo(String taskId, int stepNo);

    List<TaskStepEntity> findByTaskIdOrderByStepNo(String taskId);

    Optional<TaskStepEntity> findFirstByTaskIdAndStatusNotOrderByStepNoAsc(String taskId, String status);

    @Modifying(flushAutomatically = true)
    @Query("""
            update TaskStepEntity s set s.status = 'RUNNING', s.startedAt = :at
            where s.taskId = :taskId and s.stepNo = :stepNo
            """)
    int markRunning(@Param("taskId") String taskId, @Param("stepNo") int stepNo, @Param("at") LocalDateTime at);

    @Modifying(flushAutomatically = true)
    @Query("""
            update TaskStepEntity s set s.status = 'DONE', s.checkpoint = :checkpoint, s.endedAt = :at
            where s.taskId = :taskId and s.stepNo = :stepNo
            """)
    int markDone(@Param("taskId") String taskId, @Param("stepNo") int stepNo,
                 @Param("checkpoint") String checkpoint, @Param("at") LocalDateTime at);

    @Modifying(flushAutomatically = true)
    @Query("""
            update TaskStepEntity s set s.status = 'FAILED', s.errorMsg = :msg, s.endedAt = :at
            where s.taskId = :taskId and s.stepNo = :stepNo
            """)
    int markFailed(@Param("taskId") String taskId, @Param("stepNo") int stepNo,
                   @Param("msg") String msg, @Param("at") LocalDateTime at);
}
