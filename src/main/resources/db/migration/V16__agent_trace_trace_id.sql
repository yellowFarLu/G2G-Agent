-- V16：agent_trace 补 trace_id（AC-I1 复查回流）
-- 背景：model_call_log（V14）与 task_event（V9）已带 trace_id，
-- agent_trace 此前仅 conversation_id 关联，无法与日志/打点按 traceId 串联。
-- 取值：span 持久化时从 SLF4J MDC 读取（TraceMdcFilter 请求入口注入，
-- 跨线程经 MdcRunnable/MdcCallable 传递）；无 MDC 上下文的调用方（恢复扫描等）留 NULL。
ALTER TABLE agent_trace ADD COLUMN trace_id VARCHAR(64) NULL;
CREATE INDEX idx_agent_trace_trace_id ON agent_trace (trace_id);
