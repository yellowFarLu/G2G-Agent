package com.wikiagent.domain.eval.anomaly;

/**
 * 异常处置期望/实际结果。字段为 null 表示该维度不适用不断言。
 *
 * @param taskStatus     任务状态（com.wikiagent.domain.task.TaskStatus code）
 * @param documentStatus 文档状态（kb_document.status，如 AI_SKIPPED）
 * @param humanKind      人工任务类型（HumanTaskKind code）
 * @param errorCode      失败错误码（ErrorCode code）
 */
public record AnomalyOutcome(String taskStatus,
                             String documentStatus,
                             String humanKind,
                             String errorCode) {

    public static AnomalyOutcome taskHuman(String taskStatus, String humanKind) {
        return new AnomalyOutcome(taskStatus, null, humanKind, null);
    }

    public static AnomalyOutcome failed(String errorCode) {
        return new AnomalyOutcome("FAILED", null, null, errorCode);
    }
}
