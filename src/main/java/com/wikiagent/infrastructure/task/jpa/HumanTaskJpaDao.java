package com.wikiagent.infrastructure.task.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * human_task JPA DAO。同一接管点仅一人接管：lock_version CAS。
 */
public interface HumanTaskJpaDao extends JpaRepository<HumanTaskEntity, Long> {

    List<HumanTaskEntity> findByTaskId(String taskId);

    List<HumanTaskEntity> findByStatusInAndClaimedBy(List<String> statuses, String claimedBy);

    /** OPEN→CLAIMED 的 CAS 认领。批量更新绕过持久化上下文，clear 防止后续读到一级缓存旧实体。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update HumanTaskEntity h
            set h.status = 'CLAIMED', h.claimedBy = :userId, h.claimedAt = :now, h.lockVersion = h.lockVersion + 1
            where h.id = :id and h.status = 'OPEN' and h.lockVersion = :expectedLockVersion
            """)
    int casClaim(@Param("id") Long id,
                 @Param("userId") String userId,
                 @Param("expectedLockVersion") int expectedLockVersion,
                 @Param("now") LocalDateTime now);

    /** 终结人工任务（OPEN 或 CLAIMED 均可 resolve）。批量更新绕过持久化上下文，clear 防脏读。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update HumanTaskEntity h
            set h.status = 'RESOLVED', h.resolvedBy = :userId, h.resolvedAt = :now, h.formValue = :formValue
            where h.id = :id and h.status in ('OPEN', 'CLAIMED')
            """)
    int resolve(@Param("id") Long id,
                @Param("userId") String userId,
                @Param("formValue") String formValue,
                @Param("now") LocalDateTime now);
}
