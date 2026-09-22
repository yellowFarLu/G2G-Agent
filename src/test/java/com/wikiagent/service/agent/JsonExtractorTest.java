package com.wikiagent.service.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonExtractorTest {

    @Test
    void 应提取纯JSON对象() {
        assertEquals("{\"mode\":\"search\"}", JsonExtractor.extractObject("{\"mode\":\"search\"}"));
    }

    @Test
    void 应提取夹在说明文字中的JSON() {
        String out = "好的，以下是规划结果：\n{\"mode\":\"search\",\"queries\":[\"报销时限\"]}\n请查收。";
        assertEquals("{\"mode\":\"search\",\"queries\":[\"报销时限\"]}", JsonExtractor.extractObject(out));
    }

    @Test
    void 字符串值内的大括号与引号不应破坏配平() {
        String out = "{\"sufficient\":false,\"refinedQuery\":\"查询包含 } 或 \\\" 引号\"}";
        Map<String, Object> parsed = JsonExtractor.parseObject(out);
        assertEquals(false, parsed.get("sufficient"));
        assertEquals("查询包含 } 或 \" 引号", parsed.get("refinedQuery"));
    }

    @Test
    void 应解析嵌套对象() {
        Map<String, Object> parsed = JsonExtractor.parseObject(
                "前缀 {\"a\":{\"b\":\"{嵌套}\"},\"c\":true} 后缀");
        assertTrue(parsed.get("a") instanceof Map);
        assertEquals(true, parsed.get("c"));
    }

    @Test
    void 无JSON时返回null与空Map() {
        assertNull(JsonExtractor.extractObject("抱歉，我无法输出该格式"));
        assertTrue(JsonExtractor.parseObject("不是 JSON").isEmpty());
        assertTrue(JsonExtractor.parseObject(null).isEmpty());
        assertTrue(JsonExtractor.parseObject("{不完整的 JSON").isEmpty());
    }

    @Test
    void 仅配平的大括号块才会被采纳() {
        // 第一个 '{' 无法配平时应继续尝试后续的
        Map<String, Object> parsed = JsonExtractor.parseObject("文本 { 未闭合 {\"ok\":1}");
        assertEquals(1, parsed.get("ok"));
    }
}
