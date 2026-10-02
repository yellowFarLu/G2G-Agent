-- V7__conflict_resolution.sql: v4 §6.6.3 知识冲突检测与解决
-- 相似度 > 0.8 的知识 chunk 对，记录冲突解决决策

CREATE TABLE IF NOT EXISTS conflict_resolution (
    id                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    chunk_id_a          VARCHAR(64)  NOT NULL,       -- 冲突方 A
    chunk_id_b          VARCHAR(64)  NOT NULL,       -- 冲突方 B
    similarity          DOUBLE      NOT NULL,        -- 相似度分数
    domain_tag          VARCHAR(64),                  -- 所属领域
    sub_domain_tag      VARCHAR(64),                  -- 所属子领域
    status              VARCHAR(32)  NOT NULL,       -- DETECTED / RESOLVED / IGNORED
    resolution          VARCHAR(32),                  -- KEEP_A / KEEP_B / MERGE / DELETE_A / DELETE_B / KEEP_BOTH
    resolved_by         VARCHAR(64),                  -- 解决者 user_id
    resolution_comment  VARCHAR(2000),
    detected_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at         TIMESTAMP,
    CONSTRAINT uk_conflict_pair UNIQUE (chunk_id_a, chunk_id_b)
);

CREATE INDEX idx_conflict_status ON conflict_resolution (status);
CREATE INDEX idx_conflict_domain ON conflict_resolution (domain_tag, sub_domain_tag);
