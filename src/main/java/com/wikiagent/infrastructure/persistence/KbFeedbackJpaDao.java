package com.wikiagent.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * v4 §17 用户反馈 JPA DAO。
 */
public interface KbFeedbackJpaDao extends JpaRepository<KbFeedbackEntity, Long> {

    List<KbFeedbackEntity> findByChunkId(String chunkId);

    List<KbFeedbackEntity> findBySessionId(String sessionId);

    @Query("SELECT f.feedbackType, COUNT(f) FROM KbFeedbackEntity f WHERE f.chunkId = :chunkId GROUP BY f.feedbackType")
    List<Object[]> countByChunkIdGroupByType(String chunkId);
}
