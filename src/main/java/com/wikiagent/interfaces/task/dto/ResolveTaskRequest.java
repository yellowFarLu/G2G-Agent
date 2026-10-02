package com.wikiagent.interfaces.task.dto;

import com.fasterxml.jackson.databind.JsonNode;

/** 人工处置请求：kind=INPUT 需 formValue；kind=DIRECT_RESOLVE 可带 resultRef。 */
public record ResolveTaskRequest(String kind, JsonNode formValue, String resultRef) {
}
