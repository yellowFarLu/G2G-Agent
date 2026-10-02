package com.wikiagent.infrastructure.llm;

import com.wikiagent.domain.llm.spi.RerankRequest;
import com.wikiagent.domain.llm.spi.RerankResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2 DashScopeRerankProvider 桩测试：占位实现的契约（分数列表与候选等长、
 * available 标志透传、name 含模型名）。
 */
class DashScopeRerankProviderTest {

    @Test
    void 占位实现返回与候选等长的零分列表() {
        DashScopeRerankProvider p = new DashScopeRerankProvider("gte-rerank", false);
        RerankResult r = p.rerank(new RerankRequest("q", List.of("a", "b", "c"), 3));
        assertEquals(3, r.scores().size());
        assertTrue(r.scores().stream().allMatch(s -> s == 0.0));
    }

    @Test
    void available标志与name透传() {
        assertFalse(new DashScopeRerankProvider("m1", false).available());
        assertTrue(new DashScopeRerankProvider("m1", true).available());
        assertEquals("dashscope-rerank:m1", new DashScopeRerankProvider("m1", false).name());
    }

    @Test
    void 空候选返回空分数列表() {
        DashScopeRerankProvider p = new DashScopeRerankProvider(null, false);
        assertEquals(0, p.rerank(new RerankRequest("q", List.of(), 0)).scores().size());
    }
}
