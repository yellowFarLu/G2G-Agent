package com.wikiagent.domain.retrieve;

/**
 * v1-v2 检索结果值对象。
 */
public record RetrievalResult(
        String chunkId,
        String docId,
        String content,
        double score,           // 相似度分数
        String parentContent     // 父文档内容（上下文回填）
) {
    public static RetrievalResult of(String chunkId, String docId, String content, double score) {
        return new RetrievalResult(chunkId, docId, content, score, null);
    }
}
