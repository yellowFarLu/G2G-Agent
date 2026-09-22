package com.wikiagent.domain.tool;

import java.util.List;

/**
 * v1-v2 §2 工具定义值对象。
 */
public record ToolDefinition(
        String name,
        String description,
        List<String> parameterNames
) {
    public static ToolDefinition of(String name, String description, String... params) {
        return new ToolDefinition(name, description, List.of(params));
    }
}
