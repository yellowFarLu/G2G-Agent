package com.wikiagent.domain.parse.spi;

/**
 * 表格识别请求：单页渲染图（跨页拼接由应用层完成）。
 */
public record TableRequest(String docId, int pageNo, byte[] imageBytes, String mimeType) {
}
