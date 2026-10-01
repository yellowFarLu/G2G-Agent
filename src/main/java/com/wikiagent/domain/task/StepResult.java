package com.wikiagent.domain.task;

/**
 * 单步执行结果：skip 表示跳过本步；checkpointJson 供断点续跑；
 * progressPercent 推进任务整体进度；resultRef 为最终结果引用（仅最后一步通常给值）。
 */
public record StepResult(boolean skip, String checkpointJson, int progressPercent, String resultRef) {

    public static StepResult done(int percent) {
        return new StepResult(false, null, percent, null);
    }

    public static StepResult done(int percent, String ref) {
        return new StepResult(false, null, percent, ref);
    }

    public static StepResult checkpoint(String json) {
        return new StepResult(false, json, 0, null);
    }

    public static StepResult skipped() {
        return new StepResult(true, null, 0, null);
    }
}
