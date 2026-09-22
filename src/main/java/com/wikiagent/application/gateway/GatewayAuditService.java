package com.wikiagent.application.gateway;

import com.wikiagent.infrastructure.persistence.ContentViolationLogEntity;
import com.wikiagent.infrastructure.persistence.ContentViolationLogJpaDao;
import com.wikiagent.infrastructure.persistence.GatewayAuditLogEntity;
import com.wikiagent.infrastructure.persistence.GatewayAuditLogJpaDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * v5 §19 安全网关审计服务。
 * <p>
 * 记录每次请求经过网关的检测结果 + 内容违规详情。
 */
@Service
public class GatewayAuditService {

    private static final Logger log = LoggerFactory.getLogger(GatewayAuditService.class);

    private final GatewayAuditLogJpaDao auditDao;
    private final ContentViolationLogJpaDao violationDao;

    public GatewayAuditService(GatewayAuditLogJpaDao auditDao,
                               ContentViolationLogJpaDao violationDao) {
        this.auditDao = auditDao;
        this.violationDao = violationDao;
    }

    /**
     * 记录网关审计日志。
     */
    public Long logAudit(String userId, String sessionId, String conversationId,
                         String direction, String detectorName,
                         String detectionResult, Double riskScore,
                         String inputSummary, String outputSummary,
                         String actionTaken) {
        GatewayAuditLogEntity entity = new GatewayAuditLogEntity();
        entity.setUserId(userId);
        entity.setSessionId(sessionId);
        entity.setConversationId(conversationId);
        entity.setDirection(direction);
        entity.setDetectorName(detectorName);
        entity.setDetectionResult(detectionResult);
        entity.setRiskScore(riskScore);
        entity.setInputSummary(truncate(inputSummary, 4000));
        entity.setOutputSummary(truncate(outputSummary, 4000));
        entity.setActionTaken(actionTaken);
        auditDao.save(entity);
        log.debug("网关审计: {} {} {} → {}", direction, detectorName, detectionResult, actionTaken);
        return entity.getId();
    }

    /**
     * 记录内容违规详情。
     */
    public void logViolation(Long gatewayAuditId, String userId, String sessionId,
                             String violationType, String violationDetail,
                             String originalContent, String blockedContent,
                             String severity) {
        ContentViolationLogEntity entity = new ContentViolationLogEntity();
        entity.setGatewayAuditId(gatewayAuditId);
        entity.setUserId(userId);
        entity.setSessionId(sessionId);
        entity.setViolationType(violationType);
        entity.setViolationDetail(truncate(violationDetail, 4000));
        entity.setOriginalContent(truncate(originalContent, 4000));
        entity.setBlockedContent(truncate(blockedContent, 4000));
        entity.setSeverity(severity);
        violationDao.save(entity);
        log.warn("内容违规: {} severity={}", violationType, severity);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
