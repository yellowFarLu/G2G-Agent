-- V14__prompt_model_governance.sql: 子项目E RAG/模型治理（规格 AC-E1）
-- 2 张表：prompt_template（提示词模板版本化）/ model_call_log（模型调用全链路打点）
-- 兼容 H2 (MODE=MySQL) 与 MySQL 8；外键为软关联（不建物理 FK）

-- ============ 提示词模板（按 code+version 版本化，ACTIVE 态唯一生效）============
CREATE TABLE IF NOT EXISTS prompt_template (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code        VARCHAR(128) NOT NULL,          -- 模板标识，如 chat.system / chat.user / agent.planner
    version     INT          NOT NULL DEFAULT 1,
    content     TEXT         NOT NULL,          -- 模板内容，{placeholder} 为占位符
    status      VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',  -- DRAFT / ACTIVE / ARCHIVED
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    CONSTRAINT uk_prompt_code_ver UNIQUE (code, version)
);
CREATE INDEX IF NOT EXISTS idx_prompt_code_status ON prompt_template (code, status);

-- ============ 模型调用日志（INTENT/EXTRACT/CHAT/RERANK/JUDGE 全链路打点）============
CREATE TABLE IF NOT EXISTS model_call_log (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    trace_id      VARCHAR(64),                  -- MDC traceId，可空
    user_id       VARCHAR(64),
    session_id    VARCHAR(64),
    purpose       VARCHAR(16)  NOT NULL,        -- INTENT / EXTRACT / CHAT / RERANK / JUDGE
    provider      VARCHAR(32)  NOT NULL,        -- dashscope / noop / ...
    model         VARCHAR(64)  NOT NULL,
    tokens_in     INT,
    tokens_out    INT,
    cost_estimate DOUBLE,                       -- 按 wikiagent.llm.pricing.{model} 估算（元/1K tokens）
    latency_ms    BIGINT,
    status        VARCHAR(16)  NOT NULL,        -- OK / ERROR
    fallback_from VARCHAR(64),                  -- 降级来源模型/provider 名，可空
    created_at    TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_model_call_trace ON model_call_log (trace_id);
CREATE INDEX IF NOT EXISTS idx_model_call_purpose ON model_call_log (purpose, created_at);
CREATE INDEX IF NOT EXISTS idx_model_call_session ON model_call_log (session_id);
