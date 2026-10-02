package com.wikiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 灰度发布配置（需求10/15，{@code wikiagent.gray}）。
 * <pre>
 * wikiagent:
 *   gray:
 *     features:
 *       rerank:
 *         percent: 10            # 按身份稳定分桶放量 10%
 *         allowlist: ["admin"]   # 恒命中（优先于 percent）
 *         denylist: []           # 恒拒绝（优先于白名单）
 * </pre>
 * 未配置的特性不门控（全量放开），保证存量行为不变；新建实例 features 为空即全放开。
 */
@ConfigurationProperties(prefix = "wikiagent.gray")
public class GrayReleaseProperties {

    /** 特性名 → 灰度规则。 */
    private Map<String, Rule> features = new LinkedHashMap<>();

    public Map<String, Rule> getFeatures() {
        return features;
    }

    public void setFeatures(Map<String, Rule> features) {
        this.features = features == null ? new LinkedHashMap<>() : features;
    }

    /** 单特性灰度规则。 */
    public static class Rule {

        /** 放量百分比 0-100（默认 100 全量）。 */
        private int percent = 100;

        /** 白名单：恒命中（优先于 percent）。 */
        private List<String> allowlist = new ArrayList<>();

        /** 黑名单：恒拒绝（优先于白名单与 percent）。 */
        private List<String> denylist = new ArrayList<>();

        public int getPercent() {
            return percent;
        }

        public void setPercent(int percent) {
            this.percent = percent;
        }

        public List<String> getAllowlist() {
            return allowlist;
        }

        public void setAllowlist(List<String> allowlist) {
            this.allowlist = allowlist == null ? new ArrayList<>() : allowlist;
        }

        public List<String> getDenylist() {
            return denylist;
        }

        public void setDenylist(List<String> denylist) {
            this.denylist = denylist == null ? new ArrayList<>() : denylist;
        }
    }
}
