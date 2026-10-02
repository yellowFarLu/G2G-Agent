package com.wikiagent.domain.parse.spi;

/**
 * OCR 请求：单页/单图的字节与 MIME。pageNo 从 1 起（图片文档固定为 1）。
 */
public record OcrRequest(String docId, int pageNo, byte[] imageBytes, String mimeType) {
}
