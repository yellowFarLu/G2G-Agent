package com.wikiagent.infrastructure.trace;

import org.springframework.stereotype.Service;

/**
 * v1-v2 §10 安全护栏审计日志仓储服务。
 * <p>
 * 封装 {@link AuditLogJpaDao}，对外提供语义化的日志记录方法。
 * 每次安全拦截 / Guardrail 触发事件都会写入 audit_log 表。
 */
@Service
public class AuditLogRepository {

    private final AuditLogJpaDao dao;

    public AuditLogRepository(AuditLogJpaDao dao) {
        this.dao = dao;
    }

    /**
     * 记录一条审计日志。
     *
     * @param userId       用户 id（可空）
     * @param sessionId    会话 id（可空）
     * @param eventType    事件类型：INPUT_BLOCKED / OUTPUT_BLOCKED / TOOL_DENIED / RULE_OF_TWO_VIOLATION
     * @param guardrail    护栏类型：input_guardrail / output_guardrail / spotlighting / rule_of_two
     * @param severity     严重级别：INFO / WARN / ERROR / CRITICAL
     * @param inputSummary 输入摘要（可空，最长 2000 字符）
     * @param actionTaken  处置动作：BLOCKED / SANITIZED / ALLOWED / RETRY
     * @param detail       详细信息（可空）
     */
    public void log(String userId, String sessionId, String eventType, String guardrail,
                    String severity, String inputSummary, String actionTaken, String detail) {
        AuditLogEntity entity = new AuditLogEntity();
        entity.setUserId(userId);
        entity.setSessionId(sessionId);
        entity.setEventType(eventType);
        entity.setGuardrail(guardrail);
        entity.setSeverity(severity);
        entity.setInputSummary(truncate(inputSummary, 2000));
        entity.setActionTaken(actionTaken);
        entity.setDetail(detail);
        dao.save(entity);
    }

    /** 截断字符串到指定长度，避免超出列定义。 */
    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
