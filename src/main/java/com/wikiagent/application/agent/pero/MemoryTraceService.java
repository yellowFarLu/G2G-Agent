package com.wikiagent.application.agent.pero;

import com.wikiagent.domain.agent.Plan;
import com.wikiagent.domain.agent.PlanStep;
import com.wikiagent.domain.agent.ReActResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * v6 §20 TraceService 端口的内存默认实现（自洽兜底）。
 * <p>
 * v3-v5 实施时由 {@code MysqlTraceRepository} 替换（持久化到 MySQL {@code agent_trace} 表），
 * 本类为 v6 自洽的最简内存实现，便于编译启动与 §13.8 验收调试。
 */
@Component
public class MemoryTraceService implements TraceService {

    private static final Logger log = LoggerFactory.getLogger(MemoryTraceService.class);
    private final ConcurrentMap<String, SpanImpl> spans = new ConcurrentHashMap<>();

    @Override
    public TraceSpan start(String conversationId, String userId, String spanType, String description) {
        String spanId = UUID.randomUUID().toString();
        SpanImpl span = new SpanImpl(spanId, null, conversationId, userId, spanType, description, System.currentTimeMillis());
        spans.put(spanId, span);
        log.debug("[trace] start spanId={} type={} desc={}", spanId, spanType, description);
        return span;
    }

    @Override
    public void end(TraceSpan span, String payload, String status, String error) {
        if (!(span instanceof SpanImpl s)) {
            return;
        }
        s.endTime = System.currentTimeMillis();
        s.payload = payload;
        s.status = status;
        s.error = error;
        log.debug("[trace] end spanId={} status={} dur={}ms", s.spanId, status, s.endTime - s.startTime);
    }

    /** Span 值对象实现，同时实现 TraceSpan 端口。 */
    static class SpanImpl implements TraceSpan {
        final String spanId;
        final String parentSpanId;
        final String conversationId;
        final String userId;
        final String spanType;
        final String description;
        final long startTime;
        long endTime;
        String payload;
        String status;
        String error;

        SpanImpl(String spanId, String parentSpanId, String conversationId, String userId,
                 String spanType, String description, long startTime) {
            this.spanId = spanId;
            this.parentSpanId = parentSpanId;
            this.conversationId = conversationId;
            this.userId = userId;
            this.spanType = spanType;
            this.description = description;
            this.startTime = startTime;
        }

        @Override
        public String spanId() {
            return spanId;
        }

        @Override
        public String parentSpanId() {
            return parentSpanId;
        }
    }
}
