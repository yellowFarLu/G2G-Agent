package com.wikiagent.repo.rule;

import com.wikiagent.entity.rule.ReviewCaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReviewCaseRepo extends JpaRepository<ReviewCaseEntity, Long> {

    List<ReviewCaseEntity> findByStatusOrderByCreatedAtDesc(String status);

    List<ReviewCaseEntity> findByDocIdOrderByCreatedAtDesc(String docId);
}
