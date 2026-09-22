-- V6__feedback_metric.sql: v4 §17 用户反馈 + §6.6.2 知识指标
-- 用户对每条回答的 "有用 / 无用" 反馈 + 知识使用频率指标

-- 用户反馈记录（§17 "有用/无用" 按钮）
CREATE TABLE IF NOT EXISTS kb_feedback (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id         VARCHAR(64)  NOT NULL,
    session_id      VARCHAR(64)  NOT NULL,
    conversation_id VARCHAR(128) NOT NULL,
    chunk_id        VARCHAR(64),                   -- 被反馈的知识 chunk id
    feedback_type   VARCHAR(16)  NOT NULL,          -- USEFUL / USELESS
    feedback_comment VARCHAR(2000),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_feedback_chunk ON kb_feedback (chunk_id);
CREATE INDEX IF NOT EXISTS idx_feedback_session ON kb_feedback (session_id);

-- 知识使用指标事件（每次知识被检索/引用时记录一条）
CREATE TABLE IF NOT EXISTS metric_event (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    chunk_id        VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(32)  NOT NULL,          -- RETRIEVED / CITED / FEEDBACK_USEFUL / FEEDBACK_USELESS
    user_id         VARCHAR(64),
    session_id      VARCHAR(64),
    similarity      DOUBLE,                         -- 检索相似度分数
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_metric_chunk ON metric_event (chunk_id);
CREATE INDEX IF NOT EXISTS idx_metric_type ON metric_event (event_type);
CREATE INDEX IF NOT EXISTS idx_metric_time ON metric_event (created_at);

-- 知识指标聚合结果（§6.6.2.4 看板查询用）
CREATE TABLE IF NOT EXISTS knowledge_metric (
    chunk_id            VARCHAR(64)  NOT NULL PRIMARY KEY,
    total_retrievals    INT          NOT NULL DEFAULT 0,
    total_citations     INT          NOT NULL DEFAULT 0,
    useful_count        INT          NOT NULL DEFAULT 0,
    useless_count        INT          NOT NULL DEFAULT 0,
    recall_rate          DOUBLE,                      -- 召回率
    precision_rate       DOUBLE,                      -- 精确率
    usefulness_rate      DOUBLE,                      -- 有用率 = useful / (useful + useless)
    uselessness_rate    DOUBLE,                      -- 无用率
    usage_frequency     DOUBLE,                      -- 使用频率（带时间衰减）
    staleness_score     DOUBLE,                      -- 过期分数（越低越可能过期）
    last_used_at        TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
