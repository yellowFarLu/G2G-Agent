package com.wikiagent.infrastructure.task.jpa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.domain.task.ErrorCode;
import com.wikiagent.domain.task.TaskInstance;
import com.wikiagent.domain.task.TaskStatus;
import com.wikiagent.domain.task.ports.TaskRepositoryPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * TaskRepositoryPort 的 JPA 适配器（MySQL/H2 权威真相源）。
 */
@Repository
@ConditionalOnProperty(name = "wikiagent.task.enabled", havingValue = "true")
@Transactional
public class JpaTaskRepository implements TaskRepositoryPort {

    private final TaskInstanceJpaDao dao;
    private final ObjectMapper mapper;

    public JpaTaskRepository(TaskInstanceJpaDao dao, ObjectMapper mapper) {
        this.dao = dao;
        this.mapper = mapper;
    }

    static LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    static Instant toInstant(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.atZone(ZoneId.systemDefault()).toInstant();
    }

    @Override
    public Optional<TaskInstance> findByTaskId(String taskId) {
        return dao.findByTaskId(taskId).map(this::toRecord);
    }

    @Override
    public Optional<TaskInstance> findByBizKey(String bizKey) {
        return dao.findByBizKey(bizKey).map(this::toRecord);
    }

    @Override
    public TaskInstance save(TaskInstance t) {
        // upsert by taskId：存在则字段级覆盖，否则插入（唯一键冲突同步抛出）
        TaskInstanceEntity entity = dao.findByTaskId(t.taskId()).orElseGet(TaskInstanceEntity::new);
        boolean existing = entity.getId() != null;
        applyRecord(entity, t, existing);
        return toRecord(dao.saveAndFlush(entity));
    }

    @Override
    public List<TaskInstance> findDispatchable(Duration stuckAge, int limit) {
        Instant now = Instant.now();
        return dao.findDispatchable(toLocalDateTime(now),
                        toLocalDateTime(now.minus(stuckAge)), limit).stream()
                .map(this::toRecord)
                .toList();
    }

    @Override
    public List<TaskInstance> findExpiredLeases(Instant now, int limit) {
        return dao.findExpiredLeases(toLocalDateTime(now), limit).stream()
                .map(this::toRecord)
                .toList();
    }

    @Override
    public boolean casLease(String taskId, String workerId, Instant expireAt) {
        return dao.casLease(taskId, workerId, toLocalDateTime(expireAt), toLocalDateTime(Instant.now())) > 0;
    }

    @Override
    public boolean renewLease(String taskId, String workerId, Instant expireAt, Instant at) {
        return dao.renewLease(taskId, workerId, toLocalDateTime(expireAt), toLocalDateTime(at)) > 0;
    }

    @Override
    public void markEnqueued(String taskId, Instant at) {
        dao.markEnqueued(taskId, toLocalDateTime(at));
    }

    @Override
    public void updateControlTrace(String taskId, String suspendReason, int controlVersion) {
        dao.updateControlTrace(taskId, suspendReason, controlVersion, toLocalDateTime(Instant.now()));
    }

    @Override
    public int casCanceling(String taskId, int controlVersion) {
        return dao.casCanceling(taskId, controlVersion, toLocalDateTime(Instant.now()));
    }

    @Override
    public int casResume(String taskId, int controlVersion) {
        return dao.casResume(taskId, controlVersion, toLocalDateTime(Instant.now()));
    }

    @Override
    public long countRunningByTenant(String tenantId) {
        return dao.countByStatusAndTenantId(TaskStatus.RUNNING.name(), tenantId);
    }

    @Override
    public List<TaskInstance> search(TaskStatus status, String submittedBy, int limit) {
        return dao.search(status == null ? null : status.name(), submittedBy, limit).stream()
                .map(this::toRecord)
                .toList();
    }

    private void applyRecord(TaskInstanceEntity e, TaskInstance t, boolean existing) {
        e.setTaskId(t.taskId());
        e.setTaskType(t.taskType());
        e.setBizKey(t.bizKey());
        e.setPayload(toJson(t.payload()));
        e.setStatus(t.status().name());
        e.setAttempt(t.attempt());
        e.setMaxAttempts(t.maxAttempts());
        e.setProgressPercent(t.progressPercent());
        e.setResultRef(t.resultRef());
        e.setErrorCode(t.errorCode() == null ? null : t.errorCode().name());
        e.setErrorMsg(t.errorMsg());
        e.setIdempotencyKey(t.idempotencyKey());
        e.setSubmittedBy(t.submittedBy());
        e.setTenantId(t.tenantId());
        e.setEnqueueAt(toLocalDateTime(t.enqueueAt()));
        e.setLeaseOwner(t.leaseOwner());
        e.setLeaseExpireAt(toLocalDateTime(t.leaseExpireAt()));
        e.setHeartbeatAt(toLocalDateTime(t.heartbeatAt()));
        e.setNextRunAt(toLocalDateTime(t.nextRunAt()));
        if (!existing) {
            // 新增行：控制痕迹按提交快照落库（初始为 null/0）
            e.setSuspendReason(t.suspendReason());
            e.setControlVersion(t.controlVersion());
        }
        // 已存在行：suspendReason/controlVersion 由 TaskControlService 定向更新独占维护，
        // worker 全量保存不得覆盖（否则步骤执行期间写入的暂停痕迹会被旧快照复活/清除）
        if (e.getCreatedAt() == null) {
            e.setCreatedAt(toLocalDateTime(t.createdAt()));
        }
        if (e.getId() == null) {
            e.setUpdatedAt(toLocalDateTime(t.updatedAt()));
        } else {
            e.setUpdatedAt(toLocalDateTime(Instant.now()));
        }
    }

    private TaskInstance toRecord(TaskInstanceEntity e) {
        return new TaskInstance(
                e.getTaskId(), e.getTaskType(), e.getBizKey(),
                TaskStatus.valueOf(e.getStatus()),
                fromJson(e.getPayload()),
                e.getAttempt(), e.getMaxAttempts(), e.getProgressPercent(),
                e.getResultRef(),
                e.getErrorCode() == null ? null : ErrorCode.valueOf(e.getErrorCode()),
                e.getErrorMsg(), e.getIdempotencyKey(), e.getSubmittedBy(), e.getTenantId(),
                toInstant(e.getEnqueueAt()), e.getLeaseOwner(),
                toInstant(e.getLeaseExpireAt()), toInstant(e.getHeartbeatAt()),
                toInstant(e.getNextRunAt()), e.getSuspendReason(), e.getControlVersion(),
                toInstant(e.getCreatedAt()), toInstant(e.getUpdatedAt()));
    }

    private String toJson(JsonNode node) {
        return node == null ? "{}" : node.toString();
    }

    private JsonNode fromJson(String json) {
        try {
            return json == null ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException("task_instance.payload 反序列化失败: " + ex.getMessage(), ex);
        }
    }
}
