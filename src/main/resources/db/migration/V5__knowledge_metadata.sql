-- V5__knowledge_metadata.sql: v4 §6.6.1 知识元数据
-- 每条知识 chunk 的元数据：创建时间、创建者、创建身份、来源、版本

CREATE TABLE IF NOT EXISTS knowledge_metadata (
    id                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    chunk_id            VARCHAR(64)  NOT NULL,       -- Milvus child chunk id
    doc_id              VARCHAR(64)  NOT NULL,
    domain_tag          VARCHAR(64)  NOT NULL,       -- v3: 领域标签
    sub_domain_tag      VARCHAR(64)  NOT NULL,       -- v3: 子领域标签
    required_identity   VARCHAR(32)  NOT NULL,       -- v3: 访问所需身份
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          VARCHAR(64),                 -- 创建者 user_id
    created_identity    VARCHAR(32),                 -- 创建者身份
    source_filename     VARCHAR(255),
    version             INT          NOT NULL DEFAULT 1,
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT uk_knowledge_chunk UNIQUE (chunk_id)
);

CREATE INDEX IF NOT EXISTS idx_km_domain ON knowledge_metadata (domain_tag, sub_domain_tag);
CREATE INDEX IF NOT EXISTS idx_km_doc ON knowledge_metadata (doc_id);
CREATE INDEX IF NOT EXISTS idx_km_created ON knowledge_metadata (created_by, created_identity);
