-- V17__graph_rag.sql: 知识图谱（GraphRAG）基础表
-- 零额外依赖：图结构用 MySQL 关系表存储，无 Neo4j 亦可运行

CREATE TABLE IF NOT EXISTS graph_entity (
    id              VARCHAR(64)  NOT NULL PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    type            VARCHAR(64)  NOT NULL,              -- 实体类型：人物/组织/产品/地点/概念/事件/规则/其他
    description     TEXT,                               -- LLM 生成的实体描述摘要
    source_doc_id   VARCHAR(64)  NOT NULL,
    source_chunk_id VARCHAR(64),                        -- 关联子块（nullable，兼容旧解析路径）
    embedding_json  TEXT,                               -- 向量 JSON 数组（可选，Milvus 侧为主索引）
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL
);

CREATE INDEX idx_entity_name ON graph_entity (name);
CREATE INDEX idx_entity_type ON graph_entity (type);
CREATE INDEX idx_entity_doc ON graph_entity (source_doc_id);
CREATE INDEX idx_entity_active ON graph_entity (is_active);

-- 实体消歧：name + type 唯一约束，冲突时 upsert 合并 description
CREATE UNIQUE INDEX idx_entity_name_type ON graph_entity (name, type);

CREATE TABLE IF NOT EXISTS graph_relation (
    id              VARCHAR(64)  NOT NULL PRIMARY KEY,
    source_entity_id VARCHAR(64) NOT NULL,
    target_entity_id VARCHAR(64) NOT NULL,
    relation_type   VARCHAR(64)  NOT NULL,              -- 关系类型：属于/包含/依赖/影响/合作/对抗/引用/继承/其他
    description     TEXT,                               -- LLM 生成的关系描述
    weight          DOUBLE       NOT NULL DEFAULT 1.0,  -- 出现频次/置信度累加
    source_doc_id   VARCHAR(64)  NOT NULL,
    source_chunk_id VARCHAR(64),
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    CONSTRAINT fk_relation_source FOREIGN KEY (source_entity_id) REFERENCES graph_entity (id),
    CONSTRAINT fk_relation_target FOREIGN KEY (target_entity_id) REFERENCES graph_entity (id)
);

CREATE INDEX idx_relation_source ON graph_relation (source_entity_id);
CREATE INDEX idx_relation_target ON graph_relation (target_entity_id);
CREATE INDEX idx_relation_type ON graph_relation (relation_type);
CREATE INDEX idx_relation_doc ON graph_relation (source_doc_id);

-- 关系消歧：source + target + type 唯一约束，冲突时累加 weight 合并 description
CREATE UNIQUE INDEX idx_relation_unique ON graph_relation (source_entity_id, target_entity_id, relation_type);
