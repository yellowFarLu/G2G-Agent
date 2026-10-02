package com.wikiagent.service.agent;

import com.wikiagent.application.llm.ModelCallRecorder;
import com.wikiagent.application.prompt.PromptTemplateService;
import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.domain.llm.ModelCallLogPurpose;
import com.wikiagent.domain.llm.spi.ChatModelProvider;
import com.wikiagent.domain.llm.spi.ChatModelRequest;
import com.wikiagent.domain.llm.spi.ChatModelResponse;
import com.wikiagent.infrastructure.llm.ChatModelProviderChain;
import com.wikiagent.infrastructure.trace.RagTraceRecorder;
import com.wikiagent.service.chat.ChatStreamer;
import com.wikiagent.service.chat.FallbackAnswerService;
import com.wikiagent.service.retrieve.QueryRewriteService;
import com.wikiagent.service.retrieve.RetrievalService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺陷1：AgentRagService 非流式模型调用接 ChatModelProviderChain——
 * 走链时裸 chatModel 零调用，链负责含 fallbackFrom 的 CHAT/INTENT/JUDGE 打点；
 * 首候选失败次候选成功时 fallbackFrom 非空。
 */
class AgentRagChainWiringTest {

    private static ChatModelProvider provider(String name, boolean fail) {
        return new ChatModelProvider() {
            @Override
            public ChatModelResponse call(ChatModelRequest request) {
                if (fail) {
                    throw new RuntimeException("provider down");
                }
                return new ChatModelResponse("{\"mode\":\"search\",\"queries\":[\"报销\"]}",
                        "m", 11, 7, 3L);
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String name() {
                return name;
            }
        };
    }

    private static <T> ObjectProvider<T> provider(T bean) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> op = mock(ObjectProvider.class);
        when(op.getIfAvailable()).thenReturn(bean);
        return op;
    }

    private AgentRagService serviceWith(ChatModelProviderChain chain, ChatModel chatModel) {
        WikiAgentProperties props = new WikiAgentProperties(null, null, null,
                new WikiAgentProperties.Agent(true, 2, 3));
        return new AgentRagService(props, chatModel, mock(RetrievalService.class),
                mock(QueryRewriteService.class), mock(ChatStreamer.class),
                mock(FallbackAnswerService.class), mock(RagTraceRecorder.class),
                provider((ModelCallRecorder) null), provider((PromptTemplateService) null),
                "qwen-plus", provider(chain));
    }

    @Test
    void 非流式调用走链且裸chatModel零调用() {
        ModelCallRecorder recorder = mock(ModelCallRecorder.class);
        ChatModelProviderChain chain = new ChatModelProviderChain(
                List.of(provider("dashscope:qwen-plus", false)), 1, 0L, 10, 60);
        chain.setRecorder(recorder);
        ChatModel chatModel = mock(ChatModel.class);

        AgentRagService svc = serviceWith(chain, chatModel);
        AgentRagService.Plan plan = svc.plan("报销流程", "u1", "s1");

        assertEquals("search", plan.mode());
        assertEquals(List.of("报销"), plan.queries());
        verify(chatModel, never()).call(any(org.springframework.ai.chat.prompt.Prompt.class));
        verify(recorder).record(eq(ModelCallLogPurpose.INTENT), eq("dashscope:qwen-plus"),
                eq("m"), eq(11), eq(7), anyLong(), eq(true), isNull(), eq("u1"), eq("s1"));
    }

    @Test
    void 首候选失败次候选成功时fallbackFrom非空() {
        ModelCallRecorder recorder = mock(ModelCallRecorder.class);
        ChatModelProviderChain chain = new ChatModelProviderChain(
                List.of(provider("dashscope:qwen-plus", true),
                        provider("dashscope:qwen-turbo", false)), 1, 0L, 10, 60);
        chain.setRecorder(recorder);

        AgentRagService svc = serviceWith(chain, mock(ChatModel.class));
        AgentRagService.Plan plan = svc.plan("报销流程", "u1", "s1");

        assertEquals("search", plan.mode());
        // 首候选失败一行 ERROR（model 取 provider.name()，tokens/latency 为 null）；次候选成功行带 fallbackFrom
        verify(recorder).record(eq(ModelCallLogPurpose.INTENT), eq("dashscope:qwen-plus"),
                eq("dashscope:qwen-plus"), isNull(), isNull(), isNull(),
                eq(false), isNull(), eq("u1"), eq("s1"));
        verify(recorder).record(eq(ModelCallLogPurpose.INTENT), eq("dashscope:qwen-turbo"),
                eq("m"), eq(11), eq(7), anyLong(), eq(true),
                eq("dashscope:qwen-plus"), eq("u1"), eq("s1"));
    }
}
