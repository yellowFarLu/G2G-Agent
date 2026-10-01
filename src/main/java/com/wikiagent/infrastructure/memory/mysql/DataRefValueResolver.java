package com.wikiagent.infrastructure.memory.mysql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Task 5 确定性数据引用解析器：替代 Python extract_field_index.py。
 * <p>
 * 从交接清单 JSON 的 dataReferences 段提取 key→value 索引；
 * 将文本中的 {@code [DATA:key]} 占位符替换为实际值，缺失的 key 保留占位符原样。
 */
public class DataRefValueResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\[DATA:([A-Za-z0-9_]+)\\]");

    private final ObjectMapper mapper = new ObjectMapper();

    /** 从交接清单 JSON 提取 dataReferences 的 key→value 索引；JSON 非法返回空 Map。 */
    public Map<String, String> resolve(String checklistJson) {
        Map<String, String> out = new LinkedHashMap<>();
        if (checklistJson == null || checklistJson.isBlank()) {
            return out;
        }
        JsonNode root;
        try {
            root = mapper.readTree(checklistJson);
        } catch (Exception e) {
            return out;
        }
        JsonNode refs = root.get("dataReferences");
        if (!(refs instanceof JsonNode) || !refs.isArray()) {
            return out;
        }
        for (JsonNode item : refs) {
            JsonNode key = item.get("key");
            JsonNode value = item.get("value");
            if (key != null && !key.isNull()) {
                out.put(key.asText(), value == null || value.isNull() ? "" : value.asText());
            }
        }
        return out;
    }

    /** 将 text 中的 [DATA:key] 占位符替换为 values 中的值；缺失 key 保留占位符。 */
    public String replacePlaceholders(String text, Map<String, String> values) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String replacement = values.getOrDefault(key, m.group(0));
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
