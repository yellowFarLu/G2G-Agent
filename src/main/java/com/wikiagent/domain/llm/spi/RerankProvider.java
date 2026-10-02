package com.wikiagent.domain.llm.spi;

/**
 * 重排提供者 SPI（domain 层接口，基础设施实现）。
 * 不可用时 {@link #available()} 返回 false，调用方按原序放行。
 */
public interface RerankProvider {

    RerankResult rerank(RerankRequest request);

    boolean available();

    String name();
}
