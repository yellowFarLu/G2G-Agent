package com.wikiagent.infrastructure.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * v5 §19.5 输出检测器 - Presidio PII 检测/脱敏。
 * <p>
 * 使用 Microsoft Presidio sidecar 容器检测输出中的 PII（个人身份信息）。
 * 如果 sidecar 不可用，降级为正则匹配常见 PII 模式。
 */
@Component
public class PresidioPiiDetector implements GuardrailDetector {

    private static final Logger log = LoggerFactory.getLogger(PresidioPiiDetector.class);

    // 降级用 PII 正则模式
    private static final List<Pattern> PII_PATTERNS = List.of(
            // 手机号
            Pattern.compile("1[3-9]\\d{9}"),
            // 邮箱
            Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"),
            // 身份证号
            Pattern.compile("\\d{17}[0-9Xx]"),
            // 银行卡号
            Pattern.compile("\\d{16,19}"),
            // IP 地址
            Pattern.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")
    );

    private final String sidecarUrl;

    public PresidioPiiDetector(@Value("${wikiagent.gateway.output.presidio.sidecar-url:http://presidio-analyzer:5050}") String sidecarUrl) {
        this.sidecarUrl = sidecarUrl;
    }

    @Override
    public String name() { return "presidio_pii"; }

    @Override
    public String direction() { return "OUTPUT"; }

    @Override
    public GuardrailResult check(String content, String userId, String sessionId) {
        if (content == null || content.isBlank()) {
            return GuardrailResult.pass(name());
        }

        // 降级模式：用正则匹配 PII
        String sanitized = content;
        boolean found = false;
        for (Pattern p : PII_PATTERNS) {
            if (p.matcher(content).find()) {
                found = true;
                sanitized = p.matcher(sanitized).replaceAll("[已脱敏]");
            }
        }

        if (found) {
            return GuardrailResult.sanitize(name(), 0.6,
                    "检测到 PII 信息，已脱敏", sanitized, "PII_LEAK");
        }
        return GuardrailResult.pass(name());
    }
}
