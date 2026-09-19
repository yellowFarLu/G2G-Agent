package com.wikiagent.service.ingest;

/**
 * 文本清洗与父子两级切分。纯逻辑组件，便于单元测试。
 */
public class ChunkSplitter {

    private final int parentChars;
    private final int parentOverlap;
    private final int childChars;
    private final int childOverlap;

    public ChunkSplitter(int parentChars, int parentOverlap, int childChars, int childOverlap) {
        if (parentChars < 100 || childChars < 50) {
            throw new IllegalArgumentException("chunk sizes too small");
        }
        if (childOverlap >= childChars || parentOverlap >= parentChars) {
            throw new IllegalArgumentException("overlap must be smaller than chunk size");
        }
        this.parentChars = parentChars;
        this.parentOverlap = parentOverlap;
        this.childChars = childChars;
        this.childOverlap = childOverlap;
    }

    /**
     * 一级切分：按段落边界聚合出父块。超长段落按句子边界二次切分（带重叠）。
     */
    public java.util.List<String> splitParents(String text) {
        java.util.List<String> result = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String para : text.split("\n")) {
            String p = para.strip();
            if (p.isEmpty()) {
                continue;
            }
            if (p.length() > parentChars) {
                // 先冲刷当前累积
                if (cur.length() > 0) {
                    result.add(cur.toString());
                    cur.setLength(0);
                }
                // 超长段落：按句子切分，带 parentOverlap 重叠
                for (String piece : splitLong(p, parentChars, parentOverlap)) {
                    result.add(piece);
                }
                continue;
            }
            if (cur.length() + p.length() + 1 > parentChars && cur.length() > 0) {
                result.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) {
                cur.append('\n');
            }
            cur.append(p);
        }
        if (cur.length() > 0) {
            result.add(cur.toString());
        }
        return result;
    }

    /**
     * 二级切分：在父块内部滑窗切出子块，窗口优先在句子边界收口，相邻子块带重叠。
     */
    public java.util.List<String> splitChildren(String parentText) {
        if (parentText.length() <= childChars) {
            return java.util.List.of(parentText);
        }
        java.util.List<String> result = new java.util.ArrayList<>();
        int step = childChars - childOverlap;
        int start = 0;
        while (start < parentText.length()) {
            int end = Math.min(start + childChars, parentText.length());
            if (end < parentText.length()) {
                // 在窗口后 40% 区间内找最后的句子边界，避免硬截断
                int searchFrom = Math.max(start + (int) (childChars * 0.6), start + 1);
                int boundary = lastSentenceBoundary(parentText, searchFrom, end);
                if (boundary > start) {
                    end = boundary;
                }
            }
            result.add(parentText.substring(start, end).strip());
            if (end >= parentText.length()) {
                break;
            }
            start = Math.max(end - childOverlap, start + 1);
        }
        return result.stream().filter(s -> !s.isEmpty()).toList();
    }

    private java.util.List<String> splitLong(String para, int target, int overlap) {
        java.util.List<String> sentences = new java.util.ArrayList<>();
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < para.length(); i++) {
            s.append(para.charAt(i));
            if ("。！？!?；;\n".indexOf(para.charAt(i)) >= 0) {
                sentences.add(s.toString());
                s.setLength(0);
            }
        }
        if (s.length() > 0) {
            sentences.add(s.toString());
        }
        java.util.List<String> pieces = new java.util.ArrayList<>();
        StringBuilder piece = new StringBuilder();
        for (String sentence : sentences) {
            if (piece.length() + sentence.length() > target && piece.length() > 0) {
                pieces.add(piece.toString().strip());
                String tail = piece.substring(Math.max(0, piece.length() - overlap));
                piece.setLength(0);
                piece.append(tail);
            }
            // 单句超长兜底：硬切
            if (sentence.length() > target) {
                for (int i = 0; i < sentence.length(); i += target - overlap) {
                    String sub = sentence.substring(i, Math.min(i + target, sentence.length()));
                    if (!sub.isBlank()) {
                        pieces.add(sub.strip());
                    }
                }
                piece.setLength(0);
            } else {
                piece.append(sentence);
            }
        }
        if (piece.length() > 0) {
            pieces.add(piece.toString().strip());
        }
        return pieces.stream().filter(x -> !x.isEmpty()).toList();
    }

    private int lastSentenceBoundary(String text, int from, int to) {
        int best = -1;
        for (int i = Math.min(to, text.length()) - 1; i >= from; i--) {
            if ("。！？!?；;\n".indexOf(text.charAt(i)) >= 0) {
                best = i + 1;
                break;
            }
        }
        return best;
    }
}
