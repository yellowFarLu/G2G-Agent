package com.wikiagent.domain.task.ports;

import com.fasterxml.jackson.databind.JsonNode;
import com.wikiagent.domain.task.HumanTask;
import com.wikiagent.domain.task.HumanTaskKind;

import java.util.List;
import java.util.Optional;

/**
 * 人工接管任务仓储端口。claim 用 lock_version CAS，同一接管点仅一人成功。
 */
public interface HumanTaskRepositoryPort {

    HumanTask save(HumanTask h);

    Optional<HumanTask> findById(Long id);

    List<HumanTask> findByTaskId(String taskId);

    List<HumanTask> findOpenByUser(String claimedOrSubmittedBy);

    /** OPEN→CLAIMED 的 CAS 认领：expectedLockVersion 匹配才置 claimedBy，返回是否成功。 */
    boolean casClaim(Long id, String userId, int expectedLockVersion);

    /**
     * 条件终结人工任务（仅 OPEN/CLAIMED 可终结）。
     *
     * @return 实际更新行数；0 表示并发竞争下任务已被他人处置，调用方必须中止后续状态迁移
     */
    int resolve(Long id, String userId, JsonNode formValue, HumanTaskKind kind);
}
