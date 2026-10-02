package com.wikiagent.domain.parse.spi;

/**
 * 版面分析请求：单页渲染图（由 PDF 页面栅格化得到）。
 */
public record LayoutRequest(String docId, int pageNo, byte[] imageBytes, String mimeType) {
}
