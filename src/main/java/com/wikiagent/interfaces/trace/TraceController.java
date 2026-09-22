package com.wikiagent.interfaces.trace;

import com.wikiagent.domain.trace.TraceSpan;
import com.wikiagent.infrastructure.trace.MysqlTraceRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * v1-v2 §8 链路追踪查询接口。
 * <p>
 * 对外提供按 session / conversation / user 维度的链路追踪查询能力，
 * 返回领域值对象 {@link TraceSpan} 列表。
 */
@RestController
@RequestMapping("/api/trace")
public class TraceController {

    private final MysqlTraceRepository traceRepository;

    public TraceController(MysqlTraceRepository traceRepository) {
        this.traceRepository = traceRepository;
    }

    /** 按 sessionId 查询链路（按开始时间升序）。 */
    @GetMapping("/session/{sessionId}")
    public List<TraceSpan> getBySession(@PathVariable String sessionId) {
        return traceRepository.findBySessionId(sessionId);
    }

    /** 按 conversationId 查询链路（按开始时间升序）。 */
    @GetMapping("/conversation/{conversationId}")
    public List<TraceSpan> getByConversation(@PathVariable String conversationId) {
        return traceRepository.findByConversationId(conversationId);
    }

    /** 按 userId 查询最近 100 条 span（按开始时间降序）。 */
    @GetMapping("/user/{userId}")
    public List<TraceSpan> getByUser(@PathVariable String userId) {
        return traceRepository.findByUserId(userId);
    }
}
