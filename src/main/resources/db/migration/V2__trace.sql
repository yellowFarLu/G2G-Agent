-- V2__trace.sql: v1-v2 §8 全链路可观测 - Agent 执行链路追踪表
-- 每个 TraceSpan 一行：plan / node / tool_call / llm_call / generate

CREATE TABLE IF NOT EXISTS agent_trace (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(128) NOT NULL,          -- userId:sessionId
    user_id         VARCHAR(64)  NOT NULL,
    session_id      VARCHAR(64)  NOT NULL,
    node_id         VARCHAR(64),                    -- 对应 PlanStep.id
    span_type       VARCHAR(32)  NOT NULL,          -- plan / node / tool_call / llm_call / generate
    input_data      TEXT,                           -- 输入摘要
    output_data     TEXT,                           -- 输出摘要
    status          VARCHAR(16)  NOT NULL,          -- OK / ERROR / TRUNCATED
    error_msg       VARCHAR(2000),
    start_time      TIMESTAMP    NOT NULL,
    end_time        TIMESTAMP,
    duration_ms     BIGINT,
    token_input     INT          DEFAULT 0,
    token_output    INT          DEFAULT 0,
    intent          VARCHAR(32),                    -- v3: 意图分类标签
    model_used      VARCHAR(64)                     -- v1-v2 §7: 路由的模型名
);

CREATE INDEX idx_trace_conversation ON agent_trace (conversation_id);
CREATE INDEX idx_trace_session ON agent_trace (session_id);
CREATE INDEX idx_trace_user ON agent_trace (user_id);
