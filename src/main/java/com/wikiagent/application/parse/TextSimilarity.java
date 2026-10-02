package com.wikiagent.application.parse;

/**
 * 归一化文本相似度（1 - Levenshtein/最长串长度）。用于文本层 vs OCR 冲突判定。
 */
public final class TextSimilarity {

    private TextSimilarity() {
    }

    /** 0~1，完全相同为 1，完全不同为 0。 */
    public static double similarity(String a, String b) {
        if (a == null) {
            a = "";
        }
        if (b == null) {
            b = "";
        }
        String x = normalize(a);
        String y = normalize(b);
        if (x.isEmpty() && y.isEmpty()) {
            return 1.0;
        }
        int max = Math.max(x.length(), y.length());
        if (max == 0) {
            return 1.0;
        }
        return 1.0 - (double) levenshtein(x, y) / max;
    }

    /** 差异率 = 1 - 相似度。 */
    public static double diffRate(String a, String b) {
        return 1.0 - similarity(a, b);
    }

    /** 小写、去除标点、空白折叠（OCR 与文本层常见差异：大小写/全半角空格/换行）。 */
    static String normalize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean lastSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                if (!lastSpace && sb.length() > 0) {
                    sb.append(' ');
                    lastSpace = true;
                }
                continue;
            }
            if (isPunctuation(c)) {
                continue;
            }
            sb.append(Character.toLowerCase(c));
            lastSpace = false;
        }
        return sb.toString().trim();
    }

    private static boolean isPunctuation(char c) {
        if (c < 128) {
            return !Character.isLetterOrDigit(c) && !Character.isWhitespace(c);
        }
        // CJK 标点区间（常见 、。，；：？！「」『』（）《》等）
        return (c >= 0x3000 && c <= 0x303F) || (c >= 0xFF00 && c <= 0xFFEF);
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }
}
