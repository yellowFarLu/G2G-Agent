package com.wikiagent.application.gray;

import com.wikiagent.config.GrayReleaseProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 灰度发布决策服务（需求10/15）。
 * <p>
 * 按 {@code wikiagent.gray.features.<特性名>} 规则决策某特性对指定键
 * （通常为业务身份/用户标识）是否命中放量：
 * <ul>
 *   <li>规则优先级：denylist（恒拒） &gt; allowlist（恒中） &gt; percent 分桶；</li>
 *   <li>分桶：SHA-256(feature:key) 前 4 字节取模 100，同一 feature+key 恒定同桶，
 *       扩缩容/重启不漂移；</li>
 *   <li>未配置的特性不门控（全量放开），key 空白归入 {@code anonymous} 桶；</li>
 *   <li>每次决策打点 {@code wikiagent.gray.decision{feature,result,reason}} 并写 debug 日志，
 *       指标不可用不影响决策。</li>
 * </ul>
 * 当前决策点：rerank 重排（见 RetrievalService#maybeRerank，灰度键为当前请求身份）。
 */
@Service
public class GrayReleaseService {

    private static final Logger log = LoggerFactory.getLogger(GrayReleaseService.class);

    /** 灰度决策结果：enabled + reason（审计/排障用）。 */
    public record Decision(boolean enabled, String reason) { }

    private final GrayReleaseProperties props;
    private final MeterRegistry meterRegistry;

    public GrayReleaseService(GrayReleaseProperties props, ObjectProvider<MeterRegistry> meterRegistry) {
        this.props = props;
        this.meterRegistry = meterRegistry == null ? null : meterRegistry.getIfAvailable();
    }

    /** 特性是否对 key 命中灰度放量。 */
    public boolean isEnabled(String feature, String key) {
        return decide(feature, key).enabled();
    }

    /**
     * 灰度决策。denylist &gt; allowlist &gt; percent；未配置特性全量放开。
     *
     * @param feature 特性名（如 rerank）
     * @param key     灰度键（业务身份/用户标识；空白归入 anonymous 桶）
     */
    public Decision decide(String feature, String key) {
        if (feature == null || feature.isBlank()) {
            return record("unknown", new Decision(true, "INVALID_FEATURE"));
        }
        GrayReleaseProperties.Rule rule = props.getFeatures().get(feature);
        if (rule == null) {
            return record(feature, new Decision(true, "NOT_CONFIGURED"));
        }
        String k = key == null || key.isBlank() ? "anonymous" : key.trim();
        if (rule.getDenylist().contains(k)) {
            return record(feature, new Decision(false, "DENYLIST"));
        }
        if (rule.getAllowlist().contains(k)) {
            return record(feature, new Decision(true, "ALLOWLIST"));
        }
        int percent = rule.getPercent();
        if (percent <= 0) {
            return record(feature, new Decision(false, "PERCENT_0"));
        }
        if (percent >= 100) {
            return record(feature, new Decision(true, "PERCENT_100"));
        }
        int bucket = bucket(feature, k);
        return record(feature, new Decision(bucket < percent, "BUCKET_" + bucket));
    }

    /** 稳定分桶：sha256(feature:key) 前 4 字节取模 100。同一 feature+key 恒定同桶。 */
    static int bucket(String feature, String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((feature + ":" + key).getBytes(StandardCharsets.UTF_8));
            int v = ((digest[0] & 0xff) << 24) | ((digest[1] & 0xff) << 16)
                    | ((digest[2] & 0xff) << 8) | (digest[3] & 0xff);
            return (v & 0x7fffffff) % 100;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 打点 + debug 日志；指标写入失败不影响决策。 */
    private Decision record(String feature, Decision decision) {
        try {
            if (meterRegistry != null) {
                meterRegistry.counter("wikiagent.gray.decision",
                        "feature", feature,
                        "result", decision.enabled() ? "allowed" : "denied",
                        "reason", decision.reason()).increment();
            }
        } catch (Exception e) {
            log.debug("灰度决策打点失败（不影响决策）: {}", e.getMessage());
        }
        log.debug("灰度决策 feature={} enabled={} reason={}", feature, decision.enabled(), decision.reason());
        return decision;
    }
}
