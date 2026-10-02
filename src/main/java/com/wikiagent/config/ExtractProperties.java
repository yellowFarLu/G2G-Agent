package com.wikiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 子项目 B 结构化字段抽取配置（{@code wikiagent.extract}）。
 */
@ConfigurationProperties(prefix = "wikiagent.extract")
public class ExtractProperties {

    /** 低置信阈值：字段置信度低于该值自动转人工复核（规格 §2.1 默认 0.75）。 */
    private double lowConfidenceThreshold = 0.75;

    /** 结构化输出校验失败后的修复重试次数（规格：1 次）。 */
    private int repairAttempts = 1;

    public double getLowConfidenceThreshold() {
        return lowConfidenceThreshold;
    }

    public void setLowConfidenceThreshold(double lowConfidenceThreshold) {
        this.lowConfidenceThreshold = lowConfidenceThreshold;
    }

    public int getRepairAttempts() {
        return repairAttempts;
    }

    public void setRepairAttempts(int repairAttempts) {
        this.repairAttempts = repairAttempts;
    }
}
