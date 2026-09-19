package com.wikiagent.service.ingest;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * 文档文本清洗：
 * 1) 统一换行；2) 去零宽字符；3) 去页码/分隔线；4) 折叠多余空白；
 * 5) 去除高频重复的短行（页眉/页脚特征）。
 */
@Component
public class TextCleaner {

    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200D\\uFEFF]");
    private static final Pattern PAGE_NUMBER = Pattern.compile("^\\s*[-–——]?\\s*(\\d{1,4}|第\\s*\\d+\\s*页|Page\\s*\\d+)\\s*[-–——]?\\s*$");
    private static final Pattern SEPARATOR_LINE = Pattern.compile("^\\s*[-=_*·—―]{3,}\\s*$");
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t\\u00A0]{2,}");
    private static final Pattern MULTI_BLANK = Pattern.compile("\\n{3,}");

    public String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = TextCleaner.normal(raw);
        s = ZERO_WIDTH.matcher(s).replaceAll("");

        String[] lines = s.split("\n", -1);
        // 统计重复短行（页眉页脚特征：同一行出现 >= 3 次且长度 < 50）
        Map<String, Integer> counter = new HashMap<>();
        for (String line : lines) {
            String t = line.strip();
            if (!t.isEmpty() && t.length() < 50) {
                counter.merge(t, 1, Integer::sum);
            }
        }

        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String t = line.strip();
            if (t.isEmpty()) {
                sb.append('\n');
                continue;
            }
            if (PAGE_NUMBER.matcher(t).matches() || SEPARATOR_LINE.matcher(t).matches()) {
                continue;
            }
            if (t.length() < 50 && counter.getOrDefault(t, 0) >= 3) {
                continue; // 页眉/页脚
            }
            sb.append(MULTI_SPACE.matcher(t).replaceAll(" ")).append('\n');
        }
        s = MULTI_BLANK.matcher(sb.toString()).replaceAll("\n\n");
        return s.strip();
    }

    public static String normal(String s) {
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }
}
