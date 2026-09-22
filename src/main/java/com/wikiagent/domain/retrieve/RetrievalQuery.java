package com.wikiagent.domain.retrieve;

/**
 * v1-v2 检索查询值对象。
 */
public record RetrievalQuery(
        String originalQuery,
        String rewrittenQuery,       // 改写后的查询
        int round                     // 检索轮次
) {
    public static RetrievalQuery of(String query, int round) {
        return new RetrievalQuery(query, query, round);
    }

    public RetrievalQuery rewritten(String rewritten) {
        return new RetrievalQuery(originalQuery, rewritten, round);
    }
}
