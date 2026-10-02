package com.wikiagent.infrastructure.llm;

import com.wikiagent.domain.llm.spi.RerankProvider;
import com.wikiagent.domain.llm.spi.RerankRequest;
import com.wikiagent.domain.llm.spi.RerankResult;

import java.util.Collections;

/**
 * DashScope rerank 适配位（首批占位实现）。
 * <p>
 * 真实 DashScope text-rerank API 接线留待后续迭代；当前 {@link #available()}
 * 恒为 false，{@link RetrievalService} 侧对不可用 provider 不阻断、按原序返回。
 * 占位实现返回与候选数等长的 0 分列表，保证桩测试可断言契约。
 */
public class DashScopeRerankProvider implements RerankProvider {

    private final String model;
    private final boolean available;

    public DashScopeRerankProvider(String model, boolean available) {
        this.model = model == null ? "gte-rerank" : model;
        this.available = available;
    }

    @Override
    public RerankResult rerank(RerankRequest request) {
        int n = request == null || request.documents() == null ? 0 : request.documents().size();
        return new RerankResult(Collections.nCopies(n, 0.0));
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public String name() {
        return "dashscope-rerank:" + model;
    }
}
