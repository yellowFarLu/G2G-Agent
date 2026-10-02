-- V3__audit.sql: v1-v2 §10 安全护栏 - 审计日志表
-- 记录每次安全拦截 / Guardrail 触发事件

CREATE TABLE IF NOT EXISTS audit_log (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id         VARCHAR(64),
    session_id      VARCHAR(64),
    event_type      VARCHAR(32)  NOT NULL,          -- INPUT_BLOCKED / OUTPUT_BLOCKED / TOOL_DENIED / RULE_OF_TWO_VIOLATION
    guardrail       VARCHAR(32)  NOT NULL,          -- input_guardrail / output_guardrail / spotlighting / rule_of_two
    severity        VARCHAR(16)  NOT NULL,          -- INFO / WARN / ERROR / CRITICAL
    input_summary   VARCHAR(2000),
    output_summary  VARCHAR(2000),
    action_taken    VARCHAR(64),                    -- BLOCKED / SANITIZED / ALLOWED / RETRY
    detail          TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_session ON audit_log (session_id);
CREATE INDEX idx_audit_user ON audit_log (user_id);
CREATE INDEX idx_audit_type ON audit_log (event_type);
