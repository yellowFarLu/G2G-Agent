package com.wikiagent.infrastructure.llm;

/**
 * Rerank 提供者统一异常：HTTP 非 2xx、响应体无法解析、缺鉴权等故障一律包装为本异常，
 * 供上层（{@code RetrievalService.maybeRerank}）捕获后降级为原序并写 FAILED 打点。
 */
public class RerankProviderException extends RuntimeException {

    public RerankProviderException(String message) {
        super(message);
    }

    public RerankProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
