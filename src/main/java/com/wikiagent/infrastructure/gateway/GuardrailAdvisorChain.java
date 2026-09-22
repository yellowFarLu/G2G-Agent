package com.wikiagent.infrastructure.gateway;

import com.wikiagent.application.gateway.GatewayAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v5 §19 安全网关 - 检测器编排链。
 * <p>
 * 按配置的检测器列表依次执行，任一检测器 BLOCK 则整体 BLOCK；
 * SANITIZE 结果会累加脱敏后的内容，传递给下一个检测器。
 */
@Component
public class GuardrailAdvisorChain {

    private static final Logger log = LoggerFactory.getLogger(GuardrailAdvisorChain.class);

    private final List<GuardrailDetector> inputDetectors;
    private final List<GuardrailDetector> outputDetectors;
    private final GatewayAuditService auditService;
    private final boolean inputEnabled;
    private final boolean outputEnabled;

    public GuardrailAdvisorChain(List<GuardrailDetector> allDetectors,
                                  GatewayAuditService auditService,
                                  @Value("${wikiagent.security.input-guardrail-enabled:true}") boolean inputEnabled,
                                  @Value("${wikiagent.security.output-guardrail-enabled:true}") boolean outputEnabled) {
        this.inputDetectors = allDetectors.stream()
                .filter(d -> "INPUT".equals(d.direction()))
                .toList();
        this.outputDetectors = allDetectors.stream()
                .filter(d -> "OUTPUT".equals(d.direction()))
                .toList();
        this.auditService = auditService;
        this.inputEnabled = inputEnabled;
        this.outputEnabled = outputEnabled;
        log.info("GuardrailAdvisorChain 初始化: input detectors={}, output detectors={}",
                inputDetectors.size(), outputDetectors.size());
    }

    /**
     * 检查用户输入。
     *
     * @return ChainResult（passed, blockedReason, sanitizedContent）
     */
    public ChainResult checkInput(String content, String userId, String sessionId) {
        if (!inputEnabled) {
            return ChainResult.pass(content);
        }
        String current = content;
        for (GuardrailDetector detector : inputDetectors) {
            GuardrailResult result = detector.check(current, userId, sessionId);
            // 记录审计
            auditService.logAudit(userId, sessionId, userId + ":" + sessionId,
                    "INPUT", detector.name(),
                    result.passed() ? "PASS" : (result.sanitizedContent() != null ? "SANITIZE" : "BLOCK"),
                    result.riskScore(),
                    truncate(current, 4000), null,
                    result.passed() ? "ALLOW" : (result.sanitizedContent() != null ? "SANITIZE" : "BLOCK"));

            if (!result.passed()) {
                if (result.sanitizedContent() != null) {
                    // SANITIZE：用脱敏后的内容继续下一个检测器
                    current = result.sanitizedContent();
                    log.debug("输入被脱敏: {} → {}", detector.name(), result.reason());
                } else {
                    // BLOCK：直接拦截
                    log.warn("输入被拦截: {} risk={} reason={}", detector.name(),
                            result.riskScore(), result.reason());
                    // 记录违规详情
                    auditService.logViolation(null, userId, sessionId,
                            result.violationType(), result.reason(),
                            truncate(content, 4000), null, "HIGH");
                    return ChainResult.block(result.reason(), result.violationType());
                }
            }
        }
        return ChainResult.pass(current);
    }

    /**
     * 检查 LLM 输出。
     */
    public ChainResult checkOutput(String content, String userId, String sessionId) {
        if (!outputEnabled) {
            return ChainResult.pass(content);
        }
        String current = content;
        for (GuardrailDetector detector : outputDetectors) {
            GuardrailResult result = detector.check(current, userId, sessionId);
            // 记录审计
            auditService.logAudit(userId, sessionId, userId + ":" + sessionId,
                    "OUTPUT", detector.name(),
                    result.passed() ? "PASS" : (result.sanitizedContent() != null ? "SANITIZE" : "BLOCK"),
                    result.riskScore(),
                    null, truncate(current, 4000),
                    result.passed() ? "ALLOW" : (result.sanitizedContent() != null ? "SANITIZE" : "BLOCK"));

            if (!result.passed()) {
                if (result.sanitizedContent() != null) {
                    current = result.sanitizedContent();
                    log.debug("输出被脱敏: {} → {}", detector.name(), result.reason());
                } else {
                    log.warn("输出被拦截: {} risk={} reason={}", detector.name(),
                            result.riskScore(), result.reason());
                    auditService.logViolation(null, userId, sessionId,
                            result.violationType(), result.reason(),
                            truncate(content, 4000), null, "HIGH");
                    return ChainResult.block(result.reason(), result.violationType());
                }
            }
        }
        return ChainResult.pass(current);
    }

    public int inputDetectorCount() { return inputDetectors.size(); }
    public int outputDetectorCount() { return outputDetectors.size(); }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    /**
     * 链式检测结果。
     */
    public record ChainResult(
            boolean passed,
            String content,
            String blockedReason,
            String violationType
    ) {
        public static ChainResult pass(String content) {
            return new ChainResult(true, content, null, null);
        }

        public static ChainResult block(String reason, String violationType) {
            return new ChainResult(false, null, reason, violationType);
        }
    }
}
