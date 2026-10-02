package com.wikiagent.domain.parse.spi;

/**
 * 文档 AI 供应商调用异常。{@code retryable=true} 表示瞬时错误（超时/网络/5xx），
 * 由 ProviderExecutor 按退避策略重试；false 表示请求本身不可重试（鉴权/参数/熔断开启）。
 */
public class ParseProviderException extends RuntimeException {

    private final boolean retryable;

    public ParseProviderException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public ParseProviderException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
