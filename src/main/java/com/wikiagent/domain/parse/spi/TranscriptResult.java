package com.wikiagent.domain.parse.spi;

import java.util.List;

/**
 * 语音转写结果：全文 + 句子时间轴 + 置信度(0-1)（供应商不给定时取 1.0）。
 */
public record TranscriptResult(String fullText, List<TranscriptSegment> segments, double confidence) {

    public TranscriptResult {
        segments = segments == null ? List.of() : List.copyOf(segments);
    }
}
