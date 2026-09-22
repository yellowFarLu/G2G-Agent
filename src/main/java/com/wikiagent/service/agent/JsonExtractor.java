package com.wikiagent.service.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 从大模型输出中提取第一个结构完整的 JSON 对象或数组并解析。
 * 纯静态逻辑，便于单元测试。
 */
public final class JsonExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<Object>> LIST_TYPE = new TypeReference<>() {
    };

    private JsonExtractor() {
    }

    /** 提取第一个中括号配平的 JSON 数组字符串；找不到返回 null。 */
    public static String extractArray(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('[');
        while (start >= 0) {
            String block = balancedArray(text, start);
            if (block != null) {
                return block;
            }
            start = text.indexOf('[', start + 1);
        }
        return null;
    }

    /** 提取并解析为 List；解析失败返回空 List（调用方据此走兜底逻辑）。 */
    public static List<Object> parseArray(String text) {
        String json = extractArray(text);
        if (json == null) {
            return List.of();
        }
        try {
            List<Object> list = MAPPER.readValue(json, LIST_TYPE);
            return list == null ? List.of() : list;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 从 start 开始扫描一个中括号配平的块；字符串内忽略括号，处理转义。 */
    private static String balancedArray(String s, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '[' -> depth++;
                case ']' -> {
                    depth--;
                    if (depth == 0) {
                        return s.substring(start, i + 1);
                    }
                    if (depth < 0) {
                        return null;
                    }
                }
                default -> {
                }
            }
        }
        return null;
    }

    /** 提取第一个大括号配平的 JSON 对象字符串；找不到返回 null。 */
    public static String extractObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        while (start >= 0) {
            String block = balancedBlock(text, start);
            if (block != null) {
                return block;
            }
            start = text.indexOf('{', start + 1);
        }
        return null;
    }

    /**
     * 提取并解析为 Map；解析失败返回空 Map（调用方据此走兜底逻辑）。
     * 字符串值内的大括号、引号与转义均被正确跳过。
     */
    public static Map<String, Object> parseObject(String text) {
        String json = extractObject(text);
        if (json == null) {
            return Map.of();
        }
        try {
            Map<String, Object> map = MAPPER.readValue(json, MAP_TYPE);
            return map == null ? Map.of() : map;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 从 start 开始扫描一个括号配平的块；字符串内忽略括号，处理转义。 */
    private static String balancedBlock(String s, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{' -> depth++;
                case '}' -> {
                    depth--;
                    if (depth == 0) {
                        return s.substring(start, i + 1);
                    }
                    if (depth < 0) {
                        return null;
                    }
                }
                default -> {
                }
            }
        }
        return null;
    }
}
