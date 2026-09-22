-- V8__gateway_audit.sql: v5 §19 Agent 安全网关审计
-- 网关层拦截记录 + 内容违规记录

-- 安全网关审计日志（每次请求经过网关的检测结果）
CREATE TABLE IF NOT EXISTS gateway_audit_log (
    id                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id             VARCHAR(64),
    session_id          VARCHAR(64),
    conversation_id     VARCHAR(128),
    direction           VARCHAR(16)  NOT NULL,      -- INPUT / OUTPUT
    detector_name       VARCHAR(64)  NOT NULL,       -- keyword_blacklist / llm_judge / moderation / presidio_pii / ...
    detection_result    VARCHAR(16)  NOT NULL,       -- PASS / BLOCK / SANITIZE
    risk_score          DOUBLE,                       -- 风险分数 0.0-1.0
    input_summary       TEXT,
    output_summary      TEXT,
    action_taken        VARCHAR(64),                  -- ALLOW / BLOCK / SANITIZE / RETRY
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_gateway_session ON gateway_audit_log (session_id);
CREATE INDEX IF NOT EXISTS idx_gateway_detector ON gateway_audit_log (detector_name, direction);
CREATE INDEX IF NOT EXISTS idx_gateway_result ON gateway_audit_log (detection_result);

-- 内容违规记录（被拦截的具体违规内容）
CREATE TABLE IF NOT EXISTS content_violation_log (
    id                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    gateway_audit_id    BIGINT,                       -- 关联 gateway_audit_log.id
    user_id             VARCHAR(64),
    session_id          VARCHAR(64),
    violation_type      VARCHAR(64)  NOT NULL,       -- PROMPT_INJECTION / JAILBREAK / PII_LEAK / TOXIC_CONTENT / SYSTEM_PROMPT_LEAK / PROTECTED_MATERIAL
    violation_detail    TEXT,                         -- 检测器返回的详细违规信息
    original_content    TEXT,                         -- 原始内容（脱敏后）
    blocked_content     TEXT,                         -- 被拦截的片段
    severity            VARCHAR(16)  NOT NULL,       -- LOW / MEDIUM / HIGH / CRITICAL
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_violation_session ON content_violation_log (session_id);
CREATE INDEX IF NOT EXISTS idx_violation_type ON content_violation_log (violation_type);
CREATE INDEX IF NOT EXISTS idx_violation_severity ON content_violation_log (severity);
