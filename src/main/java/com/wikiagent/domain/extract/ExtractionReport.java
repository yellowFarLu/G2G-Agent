package com.wikiagent.domain.extract;

import java.util.List;

/**
 * 一次字段抽取的汇总：schema 版本 + 字段结果 + 是否需要人工复核及原因。
 * 需要复核的两类原因：①置信度低于阈值；②修复重试后仍有校验失败/必填缺失。
 */
public record ExtractionReport(
        String schemaKey,
        String schemaVersion,
        List<ExtractedFieldValue> fields,
        boolean needsReview,
        List<String> reviewReasons) {

    public ExtractionReport {
        fields = fields == null ? List.of() : List.copyOf(fields);
        reviewReasons = reviewReasons == null ? List.of() : List.copyOf(reviewReasons);
    }
}
