package com.wikiagent.repo.rule;

import com.wikiagent.entity.rule.RuleSetEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RuleSetRepo extends JpaRepository<RuleSetEntity, Long> {

    Optional<RuleSetEntity> findByCodeAndVersion(String code, int version);

    List<RuleSetEntity> findByCodeAndStatus(String code, String status);

    /**
     * 发布并发保护（#6）：SELECT ... FOR UPDATE 锁定同 code 全部行，
     * 把并发发布串行化，避免 check-then-save 产生两条 ACTIVE。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RuleSetEntity r where r.code = :code")
    List<RuleSetEntity> findLockByCode(@Param("code") String code);

    List<RuleSetEntity> findByCodeOrderByVersionDesc(String code);

    boolean existsByCodeAndVersion(String code, int version);
}
