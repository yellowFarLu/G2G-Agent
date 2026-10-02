package com.wikiagent.domain.parse.spi;

/**
 * 语音转写句子片段：起止毫秒（相对录音开头）+ 文本。
 */
public record TranscriptSegment(long beginMs, long endMs, String text) {
}
