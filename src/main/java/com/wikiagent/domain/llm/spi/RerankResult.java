package com.wikiagent.domain.llm.spi;

import java.util.List;

/**
 * 重排结果（domain 层 record）。
 *
 * @param scores 与请求 documents 顺序对齐的相关性分数
 */
public record RerankResult(List<Double> scores) {
}
