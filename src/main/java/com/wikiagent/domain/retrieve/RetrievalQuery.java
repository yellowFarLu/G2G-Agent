package com.wikiagent.domain.retrieve;

/**
 * v1-v2 检索查询值对象。
 * <p>
 * E5 扩展：追加 {@code filterExpression} 权限过滤表达式（如
 * {@code domain='industry' AND identity IN ('admin','product')}），null 表示不过滤。
 * 原三字段构造与 {@link #of(String, int)} / {@link #rewritten(String)} 保持不变（向后兼容）。
 */
public record RetrievalQuery(
        String originalQuery,
        String rewrittenQuery,       // 改写后的查询
        int round,                    // 检索轮次
        String filterExpression       // E5：权限过滤表达式，可空
) {
    public static RetrievalQuery of(String query, int round) {
        return new RetrievalQuery(query, query, round, null);
    }

    /** E5：单查询 + 过滤表达式。 */
    public static RetrievalQuery of(String query, String filterExpression) {
        return new RetrievalQuery(query, query, 1, filterExpression);
    }

    public RetrievalQuery rewritten(String rewritten) {
        return new RetrievalQuery(originalQuery, rewritten, round, filterExpression);
    }

    /** E5：带过滤表达式副本。 */
    public RetrievalQuery withFilter(String filterExpression) {
        return new RetrievalQuery(originalQuery, rewrittenQuery, round, filterExpression);
    }

    /** 检索用查询文本：优先 rewritten，其次 original。 */
    public String query() {
        return rewrittenQuery != null && !rewrittenQuery.isBlank() ? rewrittenQuery : originalQuery;
    }
}
