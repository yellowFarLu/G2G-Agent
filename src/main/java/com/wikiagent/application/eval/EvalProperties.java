package com.wikiagent.application.eval;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 离线评测配置（{@code wikiagent.eval}）。
 * <ul>
 *   <li>{@code enabled} 默认 <b>false</b>：评测运行器 Bean / POST /api/eval/run 默认关闭，
 *       对既有部署安全；{@code mvn test -Peval} 通过 systemProperty 打开；</li>
 *   <li>{@code outputDir} 报告目录，默认 target/eval-report。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "wikiagent.eval")
public class EvalProperties {

    private boolean enabled = false;

    private String outputDir = "target/eval-report";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(String outputDir) {
        this.outputDir = outputDir;
    }
}
