package com.wikiagent.interfaces.task.dto;

/** 提交响应：duplicate=true 表示命中既有任务（幂等语义 200+标志，不新建）。 */
public record SubmitTaskResponse(String taskId, boolean duplicate) {
}
