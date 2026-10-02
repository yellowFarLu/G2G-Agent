package com.wikiagent.repo.rule;

import com.wikiagent.entity.rule.RuleComputationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RuleComputationRepo extends JpaRepository<RuleComputationEntity, Long> {

    List<RuleComputationEntity> findByRuleCodeAndRuleVersionOrderByComputedAtDesc(String ruleCode,
                                                                                  int ruleVersion);

    List<RuleComputationEntity> findByDocIdOrderByComputedAtDesc(String docId);
}
