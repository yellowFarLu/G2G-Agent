package com.wikiagent.domain.parse.spi;

/**
 * 语音转写请求。Paraformer 文件转写 API 仅接受公网可访问 URL（HTTP/HTTPS/oss://），
 * {@code publicUrl} 由部署侧提供（如 OSS 预签名地址）；无 URL 时供应商应判定 ASR 不可用。
 */
public record AudioRequest(String docId, byte[] audioBytes, String mimeType,
                           String filename, String publicUrl) {
}
