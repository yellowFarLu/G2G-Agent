-- V13__rule_engine.sql: 子项目D 确定性规则引擎 + 人工复核（规格 §D1/D2/D4）
-- 3 张表：rule_set / rule_computation / review_case
-- 兼容 H2 (MODE=MySQL) 与 MySQL 8：JSON 列用 TEXT；外键为软关联（不建物理 FK）

-- ============ 规则集（版本化生命周期：DRAFT/ACTIVE/ARCHIVED，checksum 防篡改）============
CREATE TABLE IF NOT EXISTS rule_set (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code        VARCHAR(128) NOT NULL,
    version     INT          NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',  -- DRAFT / ACTIVE / ARCHIVED
    dsl_json    TEXT         NOT NULL,
    checksum    VARCHAR(64)  NOT NULL,                  -- dsl_json 的 sha256
    description VARCHAR(512),
    created_by  VARCHAR(64),
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    published_at TIMESTAMP   NULL,
    CONSTRAINT uk_rule_set_code_version UNIQUE (code, version)
);
CREATE INDEX idx_rule_set_code ON rule_set (code, status);

-- ============ 规则计算（输入/中间量/输出全留存，重算回放的判定依据）============
CREATE TABLE IF NOT EXISTS rule_computation (
    id                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    rule_code          VARCHAR(128) NOT NULL,
    rule_version       INT          NOT NULL,
    doc_id             VARCHAR(64),                       -- 可空：独立重算可不锚定文档
    input_snapshot     TEXT         NOT NULL,             -- 输入 JSON 快照
    intermediates_json TEXT,                              -- 中间量 JSON（每步产出）
    output_json        TEXT,                              -- 输出 JSON
    status             VARCHAR(16)  NOT NULL,             -- SUCCESS / FAILED
    error              VARCHAR(512),
    duration_ms        BIGINT       NOT NULL DEFAULT 0,
    trace_id           VARCHAR(64),
    computed_at        TIMESTAMP    NOT NULL
);
CREATE INDEX idx_rule_comp_rule ON rule_computation (rule_code, rule_version, computed_at);
CREATE INDEX idx_rule_comp_doc ON rule_computation (doc_id);

-- ============ 复核案件（低置信/材料差异/规则比对差异 → REVIEW 人工任务聚合）============
CREATE TABLE IF NOT EXISTS review_case (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    case_type       VARCHAR(32)  NOT NULL,                -- LOW_CONFIDENCE / MATERIAL_DIFF / RULE_MISMATCH
    doc_id          VARCHAR(64),
    version_no      INT,
    field_key       VARCHAR(128),
    diff_json       TEXT,                                 -- 字段级差异清单 [{fieldKey,valueA,valueB,match}]
    source          VARCHAR(16),                          -- 初审来源 MODEL / RULE / HUMAN
    confidence      DOUBLE,
    status          VARCHAR(16)  NOT NULL DEFAULT 'OPEN', -- OPEN / APPROVED / REJECTED / EDITED
    human_task_id   BIGINT,                               -- 关联 A human_task（可空）
    task_id         VARCHAR(64),                          -- 关联任务（可空，用于 task_event 留痕）
    rule_code       VARCHAR(128),
    rule_version    INT,
    computation_id  BIGINT,                               -- 来源 rule_computation（可空）
    resolution_json TEXT,                                 -- 处置内容（动作/编辑新旧值）
    resolved_by     VARCHAR(64),
    created_at      TIMESTAMP    NOT NULL,
    resolved_at     TIMESTAMP    NULL
);
CREATE INDEX idx_review_case_status ON review_case (status, created_at);
CREATE INDEX idx_review_case_doc ON review_case (doc_id);
CREATE INDEX idx_review_case_task ON review_case (task_id);
