package com.wikiagent.infrastructure.llm;

import com.wikiagent.domain.llm.spi.ChatModelProvider;
import com.wikiagent.domain.llm.spi.ChatModelRequest;
import com.wikiagent.domain.llm.spi.ChatModelResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2 降级链测试：优先级尝试、失败降级、fallbackFrom、熔断跳过、末端 NoOp 兜底。
 */
class ChatModelProviderChainTest {

    private static ChatModelProvider ok(String name, String content) {
        return new ChatModelProvider() {
            @Override public ChatModelResponse call(ChatModelRequest r) {
                return new ChatModelResponse(content, name, 1, 2, 5L);
            }
            @Override public boolean available() { return true; }
            @Override public String name() { return name; }
        };
    }

    private static ChatModelProvider failing(String name) {
        return new ChatModelProvider() {
            @Override public ChatModelResponse call(ChatModelRequest r) {
                throw new RuntimeException("boom-" + name);
            }
            @Override public boolean available() { return true; }
            @Override public String name() { return name; }
        };
    }

    private static ChatModelProvider unavailable(String name) {
        return new ChatModelProvider() {
            @Override public ChatModelResponse call(ChatModelRequest r) {
                return new ChatModelResponse("noop", name, 0, 0, 0L);
            }
            @Override public boolean available() { return false; }
            @Override public String name() { return name; }
        };
    }

    private static ChatModelProviderChain chain(List<ChatModelProvider> ps) {
        return new ChatModelProviderChain(ps, 1, 0, 3, 60);
    }

    @Test
    void 首个可用provider直接返回无降级() {
        var chain = chain(List.of(ok("p1", "hello")));
        var r = chain.call(ChatModelRequest.of("s", "u"));
        assertEquals("hello", r.response().content());
        assertNull(r.fallbackFrom());
        assertFalse(r.degraded());
    }

    @Test
    void 首失败降级到次并打fallbackFrom() {
        var chain = chain(List.of(failing("p1"), ok("p2", "ok2")));
        var r = chain.call(ChatModelRequest.of("s", "u"));
        assertEquals("ok2", r.response().content());
        assertEquals("p1", r.fallbackFrom());
        assertTrue(r.degraded());
    }

    @Test
    void 不可用provider被跳过并记fallbackFrom() {
        var chain = chain(List.of(unavailable("p0"), ok("p1", "ok1")));
        var r = chain.call(ChatModelRequest.of("s", "u"));
        assertEquals("ok1", r.response().content());
        assertEquals("p0", r.fallbackFrom());
    }

    @Test
    void 熔断打开后跳过该provider() {
        var breaker = new LlmCircuitBreaker(1, 600, null);
        ChatModelProvider p1 = failing("p1");
        var chain = new ChatModelProviderChain(List.of(p1, ok("p2", "ok2")), 1, 0, breaker);
        chain.call(ChatModelRequest.of("s", "u")); // p1 失败 1 次，阈值 1 → 熔断
        assertTrue(breaker.isOpen("p1"));
        var r = chain.call(ChatModelRequest.of("s", "u"));
        assertEquals("ok2", r.response().content());
        assertEquals("p1", r.fallbackFrom());
    }

    @Test
    void 全链失败返回空响应兜底不抛异常() {
        var chain = chain(List.of(failing("p1"), new NoOpChatModelProvider("qwen-plus")));
        var r = chain.call(ChatModelRequest.of("s", "u"));
        assertEquals("", r.response().content());
        assertTrue(r.degraded());
        assertEquals("p1", r.fallbackFrom());
    }

    @Test
    void NoOpProvider作为降级末端即使不可用也被执行() {
        var chain = chain(List.of(failing("p1"), new NoOpChatModelProvider("qwen-plus")));
        var r = chain.call(ChatModelRequest.of("s", "u"));
        assertEquals("qwen-plus", r.response().model());
        assertEquals("p1", r.fallbackFrom());
    }
}
