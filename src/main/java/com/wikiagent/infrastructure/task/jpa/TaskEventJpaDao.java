package com.wikiagent.infrastructure.task.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * task_event JPA DAO（追加式）。
 */
public interface TaskEventJpaDao extends JpaRepository<TaskEventEntity, Long> {

    List<TaskEventEntity> findByTaskIdOrderByIdAsc(String taskId);
}
