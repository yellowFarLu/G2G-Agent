package com.wikiagent.domain.eval;

import java.util.Set;

/**
 * 评测运行选项。
 *
 * @param outputDir 报告输出目录（默认 target/eval-report，可被 -Dwikiagent.eval.output-dir 覆盖）
 * @param categories 本次运行的类别集合；null/空表示四类全跑
 * @param runId      运行 ID；null 时由运行器生成
 * @param live       是否真实 LLM 运行（false=纯离线录制固件；true 仅夜间手动 -Dgroups=LiveLLM）
 */
public record EvalOptions(String outputDir,
                          Set<EvalCategory> categories,
                          String runId,
                          boolean live) {

    public static final String DEFAULT_OUTPUT_DIR = "target/eval-report";

    public static EvalOptions offlineDefault() {
        return new EvalOptions(DEFAULT_OUTPUT_DIR, null, null, false);
    }

    public boolean includes(EvalCategory category) {
        return categories == null || categories.isEmpty() || categories.contains(category);
    }
}
