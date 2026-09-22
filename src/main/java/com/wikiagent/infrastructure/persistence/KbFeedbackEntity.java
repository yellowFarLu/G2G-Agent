package com.wikiagent.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * v4 §17 用户反馈 JPA 实体（V6__feedback_metric.sql - kb_feedback 表）。
 * "有用 / 无用" 按钮反馈记录。
 */
@Entity
@Table(name = "kb_feedback")
public class KbFeedbackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "conversation_id", nullable = false)
    private String conversationId;

    @Column(name = "chunk_id")
    private String chunkId;

    @Column(name = "feedback_type", nullable = false)
    private String feedbackType;  // USEFUL / USELESS

    @Column(name = "feedback_comment", length = 2000)
    private String feedbackComment;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getChunkId() { return chunkId; }
    public void setChunkId(String chunkId) { this.chunkId = chunkId; }
    public String getFeedbackType() { return feedbackType; }
    public void setFeedbackType(String feedbackType) { this.feedbackType = feedbackType; }
    public String getFeedbackComment() { return feedbackComment; }
    public void setFeedbackComment(String feedbackComment) { this.feedbackComment = feedbackComment; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
