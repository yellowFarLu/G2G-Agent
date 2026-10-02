package com.wikiagent.infrastructure.task.jpa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.domain.task.ActorType;
import com.wikiagent.domain.task.TaskEvent;
import com.wikiagent.domain.task.TaskEventType;
import com.wikiagent.domain.task.ports.TaskEventRepositoryPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * TaskEventRepositoryPort 的 JPA 适配器（追加写）。
 */
@Repository
@ConditionalOnProperty(name = "wikiagent.task.enabled", havingValue = "true")
@Transactional
public class JpaTaskEventRepository implements TaskEventRepositoryPort {

    private final TaskEventJpaDao dao;
    private final ObjectMapper mapper;

    public JpaTaskEventRepository(TaskEventJpaDao dao, ObjectMapper mapper) {
        this.dao = dao;
        this.mapper = mapper;
    }

    @Override
    public void append(TaskEvent e) {
        TaskEventEntity entity = new TaskEventEntity();
        entity.setTaskId(e.taskId());
        entity.setEventType(e.eventType().name());
        entity.setActorType(e.actorType().name());
        entity.setActorId(e.actorId());
        entity.setDetail(e.detail() == null ? null : e.detail().toString());
        entity.setCreatedAt(JpaTaskRepository.toLocalDateTime(e.createdAt()));
        dao.saveAndFlush(entity);
    }

    @Override
    public List<TaskEvent> findByTaskId(String taskId) {
        return dao.findByTaskIdOrderByIdAsc(taskId).stream().map(this::toRecord).toList();
    }

    private TaskEvent toRecord(TaskEventEntity e) {
        return new TaskEvent(e.getTaskId(), TaskEventType.valueOf(e.getEventType()),
                ActorType.valueOf(e.getActorType()), e.getActorId(),
                parseDetail(e.getDetail()), JpaTaskRepository.toInstant(e.getCreatedAt()));
    }

    private JsonNode parseDetail(String detail) {
        try {
            return detail == null ? null : mapper.readTree(detail);
        } catch (Exception ex) {
            throw new IllegalStateException("task_event.detail 反序列化失败: " + ex.getMessage(), ex);
        }
    }
}
