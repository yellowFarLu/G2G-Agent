package com.wikiagent.service.ingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextCleanerTest {

    private final TextCleaner cleaner = new TextCleaner();

    @Test
    void 应统一换行符() {
        assertEquals("a\nb\nc", TextCleaner.normal("a\r\nb\rc"));
    }

    @Test
    void 应去除零宽字符() {
        String cleaned = cleaner.clean("报\u200B销\u200C流\uFEFF程");
        assertEquals("报销流程", cleaned);
    }

    @Test
    void 应去除页码与分隔线() {
        String raw = """
                标题行
                ---
                12
                第 3 页
                Page 4
                正文内容
                """;
        String cleaned = cleaner.clean(raw);
        assertFalse(cleaned.contains("---"));
        assertFalse(cleaned.contains("第 3 页"));
        assertFalse(cleaned.contains("Page 4"));
        assertTrue(cleaned.contains("标题行"));
        assertTrue(cleaned.contains("正文内容"));
    }

    @Test
    void 应去除高频重复短行() {
        String header = "XX公司机密文件";
        String raw = header + "\n内容一\n" + header + "\n内容二\n" + header + "\n内容三\n";
        String cleaned = cleaner.clean(raw);
        assertFalse(cleaned.contains(header));
        assertTrue(cleaned.contains("内容三"));
    }

    @Test
    void 应折叠多余空白() {
        String cleaned = cleaner.clean("a   b\t\tc\n\n\n\nd");
        assertEquals("a b c\n\nd", cleaned);
    }

    @Test
    void 空输入返回空串() {
        assertEquals("", cleaner.clean(""));
        assertEquals("", cleaner.clean(null));
    }
}
