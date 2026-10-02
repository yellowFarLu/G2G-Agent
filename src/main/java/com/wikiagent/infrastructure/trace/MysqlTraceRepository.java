package com.wikiagent.infrastructure.trace;

import com.wikiagent.domain.trace.TraceSpan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * v1-v2 §8 链路追踪 MySQL 持久化实现。
 * <p>
 * 将领域值对象 {@link TraceSpan} 与 JPA 实体 {@link TraceSpanEntity} 相互转换，
 * 通过 {@link TraceSpanJpaDao} 持久化到 agent_trace 表。
 * <p>
 * 开关：{@code wikiagent.trace.adapter=mysql}（默认开启）。
 * 注意：与 v6 pero/{@link com.wikiagent.application.agent.pero.TraceService} 接口不同，
 * 本类服务于 v1-v2 §8 可观测层的领域 TraceSpan。
 */
@Service
@ConditionalOnProperty(name = "wikiagent.trace.adapter", havingValue = "mysql", matchIfMissing = true)
public class MysqlTraceRepository {

    private final TraceSpanJpaDao dao;

    public MysqlTraceRepository(TraceSpanJpaDao dao) {
        this.dao = dao;
    }

    /** 保存一个 TraceSpan（领域 record → JPA entity）。 */
    public TraceSpan saveSpan(TraceSpan span) {
        TraceSpanEntity entity = toEntity(span);
        TraceSpanEntity saved = dao.save(entity);
        return toDomain(saved);
    }

    /** 按 sessionId 查询链路（按开始时间升序）。 */
    public List<TraceSpan> findBySessionId(String sessionId) {
        return dao.findBySessionIdOrderByStartTime(sessionId).stream()
                .map(MysqlTraceRepository::toDomain)
                .toList();
    }

    /** 按 conversationId 查询链路（按开始时间升序）。 */
    public List<TraceSpan> findByConversationId(String conversationId) {
        return dao.findByConversationIdOrderByStartTime(conversationId).stream()
                .map(MysqlTraceRepository::toDomain)
                .toList();
    }

    /** 按 userId 查询最近 100 条 span（按开始时间降序）。 */
    public List<TraceSpan> findByUserId(String userId) {
        return dao.findRecentByUserId(userId, PageRequest.of(0, 100)).stream()
                .map(MysqlTraceRepository::toDomain)
                .toList();
    }

    /** 领域 record → JPA entity。 */
    private static TraceSpanEntity toEntity(TraceSpan span) {
        TraceSpanEntity e = new TraceSpanEntity();
        e.setId(span.id() > 0 ? span.id() : null);
        e.setConversationId(span.conversationId());
        e.setUserId(span.userId());
        e.setSessionId(span.sessionId());
        e.setNodeId(span.nodeId());
        e.setSpanType(span.spanType());
        e.setInputData(span.inputData());
        e.setOutputData(span.outputData());
        e.setStatus(span.status());
        e.setErrorMsg(span.errorMsg());
        e.setStartTime(span.startTime());
        e.setEndTime(span.endTime());
        e.setDurationMs(span.durationMs());
        e.setTokenInput(span.tokenInput());
        e.setTokenOutput(span.tokenOutput());
        e.setIntent(span.intent());
        e.setModelUsed(span.modelUsed());
        // AC-I1（V16）：traceId 持久化时取自 MDC，与日志/model_call_log/task_event 串联；
        // 无 MDC 上下文的调用（恢复扫描、异步脱管线程）落 NULL，不阻断写入。
        e.setTraceId(org.slf4j.MDC.get("traceId"));
        return e;
    }

    /** JPA entity → 领域 record。 */
    private static TraceSpan toDomain(TraceSpanEntity e) {
        return new TraceSpan(
                e.getId() == null ? 0 : e.getId(),
                e.getConversationId(),
                e.getUserId(),
                e.getSessionId(),
                e.getNodeId(),
                e.getSpanType(),
                e.getInputData(),
                e.getOutputData(),
                e.getStatus(),
                e.getErrorMsg(),
                e.getStartTime(),
                e.getEndTime(),
                e.getDurationMs() == null ? 0 : e.getDurationMs(),
                e.getTokenInput() == null ? 0 : e.getTokenInput(),
                e.getTokenOutput() == null ? 0 : e.getTokenOutput(),
                e.getIntent(),
                e.getModelUsed()
        );
    }
}
