package com.wikiagent.repo.rule;

import com.wikiagent.entity.rule.ReviewCaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewCaseRepo extends JpaRepository<ReviewCaseEntity, Long> {

    List<ReviewCaseEntity> findByStatusOrderByCreatedAtDesc(String status);

    List<ReviewCaseEntity> findByDocIdOrderByCreatedAtDesc(String docId);

    /** 任务维度的案件（复核闸门：同任务仍有 OPEN 案件时不恢复流水线）。 */
    List<ReviewCaseEntity> findByTaskId(String taskId);

    List<ReviewCaseEntity> findByTaskIdAndStatus(String taskId, String status);

    /** #8 同文档+规则+类型的未结案件（材料差异重算幂等，OPEN 期间不重复建案）。 */
    Optional<ReviewCaseEntity> findFirstByDocIdAndRuleCodeAndCaseTypeAndStatusOrderByIdDesc(
            String docId, String ruleCode, String caseType, String status);
}
