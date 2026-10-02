package com.wikiagent.application.llm;

import com.wikiagent.domain.llm.ModelCallLogPurpose;
import com.wikiagent.domain.llm.spi.ChatModelProvider;
import com.wikiagent.domain.llm.spi.ChatModelRequest;
import com.wikiagent.domain.llm.spi.ChatModelResponse;
import com.wikiagent.infrastructure.llm.ChatModelProviderChain;
import com.wikiagent.infrastructure.llm.NoOpChatModelProvider;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E4 打点测试：成本估算、链打点含 fallbackFrom、降级时 fallbackFrom 非空。
 */
class ModelCallRecordingTest {

    @Test
    void 单价配置估算成本() {
        Environment env = mock(Environment.class);
        when(env.getProperty("wikiagent.llm.pricing.qwen-plus")).thenReturn("0.0008:0.002");
        ModelPricingService pricing = new ModelPricingService(env);

        // 1000 in * 0.0008 + 500 out * 0.002 = 0.0008 + 0.001 = 0.0018
        Double cost = pricing.estimate("qwen-plus", 1000, 500);
        assertNotNull(cost);
        assertEquals(0.0018, cost, 1e-9);

        assertNull(pricing.estimate("unknown-model", 1, 1));
        assertNull(pricing.estimate("qwen-plus", null, 1));
    }

    @Test
    void 链打点成功记录token与延迟() {
        ModelCallRecorder recorder = mock(ModelCallRecorder.class);
        ChatModelProvider ok = new ChatModelProvider() {
            @Override public ChatModelResponse call(ChatModelRequest r) {
                return new ChatModelResponse("答案", "qwen-plus", 10, 20, 120L);
            }
            @Override public boolean available() { return true; }
            @Override public String name() { return "dashscope:qwen-plus"; }
        };
        ChatModelProviderChain chain = new ChatModelProviderChain(List.of(ok), 1, 0, 3, 60);
        chain.setRecorder(recorder);

        chain.call(ChatModelRequest.of("s", "u"), ModelCallLogPurpose.CHAT, "u1", "s1");
        verify(recorder).record(eq(ModelCallLogPurpose.CHAT), eq("dashscope:qwen-plus"), eq("qwen-plus"),
                eq(10), eq(20), eq(120L), eq(true), isNull(), eq("u1"), eq("s1"));
    }

    @Test
    void 降级时打点fallbackFrom非空() {
        ModelCallRecorder recorder = mock(ModelCallRecorder.class);
        ChatModelProvider failing = new ChatModelProvider() {
            @Override public ChatModelResponse call(ChatModelRequest r) { throw new RuntimeException("down"); }
            @Override public boolean available() { return true; }
            @Override public String name() { return "dashscope:qwen-plus"; }
        };
        ChatModelProviderChain chain = new ChatModelProviderChain(
                List.of(failing, new NoOpChatModelProvider("qwen-plus")), 1, 0, 3, 60);
        chain.setRecorder(recorder);

        var r = chain.call(ChatModelRequest.of("s", "u"), ModelCallLogPurpose.CHAT, "u1", "s1");
        assertTrue(r.degraded());
        assertEquals("dashscope:qwen-plus", r.fallbackFrom());
        // 失败一条（ERROR）+ 末端成功一条（OK，带 fallbackFrom）
        verify(recorder).record(eq(ModelCallLogPurpose.CHAT), eq("dashscope:qwen-plus"), any(),
                isNull(), isNull(), isNull(), eq(false), isNull(), eq("u1"), eq("s1"));
        verify(recorder).record(eq(ModelCallLogPurpose.CHAT), eq("noop:qwen-plus"), eq("qwen-plus"),
                eq(0), eq(0), eq(0L), eq(true), eq("dashscope:qwen-plus"), eq("u1"), eq("s1"));
    }
}
