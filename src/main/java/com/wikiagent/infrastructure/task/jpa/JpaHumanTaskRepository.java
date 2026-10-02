package com.wikiagent.infrastructure.task.jpa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.domain.task.HumanTask;
import com.wikiagent.domain.task.HumanTaskKind;
import com.wikiagent.domain.task.HumanTaskStatus;
import com.wikiagent.domain.task.ports.HumanTaskRepositoryPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * HumanTaskRepositoryPort 的 JPA 适配器。
 */
@Repository
@ConditionalOnProperty(name = "wikiagent.task.enabled", havingValue = "true")
@Transactional
public class JpaHumanTaskRepository implements HumanTaskRepositoryPort {

    private final HumanTaskJpaDao dao;
    private final ObjectMapper mapper;

    public JpaHumanTaskRepository(HumanTaskJpaDao dao, ObjectMapper mapper) {
        this.dao = dao;
        this.mapper = mapper;
    }

    @Override
    public HumanTask save(HumanTask h) {
        HumanTaskEntity e = h.id() == null ? new HumanTaskEntity()
                : dao.findById(h.id()).orElseGet(HumanTaskEntity::new);
        applyRecord(e, h);
        return toRecord(dao.saveAndFlush(e));
    }

    @Override
    public Optional<HumanTask> findById(Long id) {
        return dao.findById(id).map(this::toRecord);
    }

    @Override
    public List<HumanTask> findByTaskId(String taskId) {
        return dao.findByTaskId(taskId).stream().map(this::toRecord).toList();
    }

    @Override
    public List<HumanTask> findOpenByUser(String claimedOrSubmittedBy) {
        return dao.findByStatusInAndClaimedBy(List.of("OPEN", "CLAIMED"), claimedOrSubmittedBy)
                .stream().map(this::toRecord).toList();
    }

    @Override
    public boolean casClaim(Long id, String userId, int expectedLockVersion) {
        return dao.casClaim(id, userId, expectedLockVersion, JpaTaskRepository.toLocalDateTime(Instant.now())) > 0;
    }

    @Override
    public int resolve(Long id, String userId, JsonNode formValue, HumanTaskKind kind) {
        // kind 参数保留端口签名一致性（INPUT/DIRECT_RESOLVE 终结动作相同，语义由调用方记录事件）
        return dao.resolve(id, userId, formValue == null ? null : formValue.toString(),
                JpaTaskRepository.toLocalDateTime(Instant.now()));
    }

    private void applyRecord(HumanTaskEntity e, HumanTask h) {
        e.setTaskId(h.taskId());
        e.setStepNo(h.stepNo());
        e.setKind(h.kind().name());
        e.setTitle(h.title());
        e.setInstruction(h.instruction());
        e.setFormSchema(h.formSchema() == null ? null : h.formSchema().toString());
        e.setFormValue(h.formValue() == null ? null : h.formValue().toString());
        e.setStatus(h.status().name());
        e.setClaimedBy(h.claimedBy());
        e.setClaimedAt(JpaTaskRepository.toLocalDateTime(h.claimedAt()));
        e.setResolvedBy(h.resolvedBy());
        e.setResolvedAt(JpaTaskRepository.toLocalDateTime(h.resolvedAt()));
        e.setLockVersion(h.lockVersion());
        e.setCreatedAt(JpaTaskRepository.toLocalDateTime(
                h.createdAt() == null ? Instant.now() : h.createdAt()));
    }

    private HumanTask toRecord(HumanTaskEntity e) {
        return new HumanTask(e.getId(), e.getTaskId(), e.getStepNo(),
                HumanTaskKind.valueOf(e.getKind()), e.getTitle(), e.getInstruction(),
                parse(e.getFormSchema()), parse(e.getFormValue()),
                HumanTaskStatus.valueOf(e.getStatus()), e.getClaimedBy(),
                JpaTaskRepository.toInstant(e.getClaimedAt()), e.getResolvedBy(),
                JpaTaskRepository.toInstant(e.getResolvedAt()), e.getLockVersion(),
                JpaTaskRepository.toInstant(e.getCreatedAt()));
    }

    private JsonNode parse(String json) {
        try {
            return json == null ? null : mapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException("human_task JSON 反序列化失败: " + ex.getMessage(), ex);
        }
    }
}
