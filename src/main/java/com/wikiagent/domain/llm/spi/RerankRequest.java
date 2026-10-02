package com.wikiagent.domain.llm.spi;

import java.util.List;

/**
 * 重排请求（domain 层 record）。
 *
 * @param query     原始查询
 * @param documents 候选文档文本（顺序与返回 scores 对齐）
 * @param topN      期望返回的重排条数上限；<=0 表示全部
 */
public record RerankRequest(String query, List<String> documents, int topN) {
}
