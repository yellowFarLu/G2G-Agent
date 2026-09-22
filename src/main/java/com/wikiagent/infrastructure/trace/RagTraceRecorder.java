package com.wikiagent.infrastructure.trace;

import com.wikiagent.domain.trace.TraceSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Agentic RAG 主链路的最小 trace 埋点（实施校正 2026-09-22）。
 * <p>
 * 背景：业务前端按 sessionId 在「可观测」页查询执行路径，但 {@code agent_trace}
 * 此前只有 PERO Agent 路径写入，默认 Agentic RAG 链路零 span。
 * 本类为路由/检索/兜底/生成四个关键节点各落一个 span，
 * conversationId 约定为 {@code userId:sessionId}（与 TraceId 约定一致）。
 * <p>
 * 硬约束：埋点失败只记日志，绝不影响问答主链路。
 */
@Service
public class RagTraceRecorder {

    private static final Logger log = LoggerFactory.getLogger(RagTraceRecorder.class);

    private final MysqlTraceRepository repository;

    public RagTraceRecorder(MysqlTraceRepository repository) {
        this.repository = repository;
    }

    /** 记录一个已完成的 span（毫秒级耗时由内部起止时间计算）。 */
    public void record(String userId, String sessionId, String nodeId, String spanType,
                       String inputData, String outputData, String status, String errorMsg) {
        try {
            String conversationId = userId + ":" + sessionId;
            TraceSpan span = TraceSpan.start(conversationId, userId, sessionId, nodeId, spanType,
                            truncate(inputData))
                    .complete(truncate(outputData), status, errorMsg);
            repository.saveSpan(span);
        } catch (Exception e) {
            log.debug("trace span 写入失败 node={}: {}", nodeId, e.getMessage());
        }
    }

    /** 大字段截断，避免超长问题文本/证据撑爆 trace 表。 */
    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 1000 ? s : s.substring(0, 1000) + "…(truncated)";
    }
}
