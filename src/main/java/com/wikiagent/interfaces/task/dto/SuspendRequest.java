package com.wikiagent.interfaces.task.dto;

/** 暂停/取消请求：expectedVersion 为空表示不校验（乐观版本防旧指令覆盖）。 */
public record SuspendRequest(Integer expectedVersion) {
}
