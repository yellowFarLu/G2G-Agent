package com.wikiagent.repo.rule;

import com.wikiagent.entity.rule.RuleSetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RuleSetRepo extends JpaRepository<RuleSetEntity, Long> {

    Optional<RuleSetEntity> findByCodeAndVersion(String code, int version);

    List<RuleSetEntity> findByCodeAndStatus(String code, String status);

    List<RuleSetEntity> findByCodeOrderByVersionDesc(String code);

    boolean existsByCodeAndVersion(String code, int version);
}
