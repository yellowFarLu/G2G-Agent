package com.wikiagent.infrastructure.llm;

import com.wikiagent.application.llm.ModelCallRecorder;
import com.wikiagent.domain.llm.ModelCallLogPurpose;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * 缺陷1 流式降级链测试：首包前失败切下一候选并带 fallbackFrom、
 * Usage 跨帧累加（取不到记 0）、首包后失败不重放而传播错误。
 */
class StreamingChatChainTest {

    private static ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static ChatResponse chunk(String text, Integer promptTokens, Integer completionTokens) {
        ChatResponseMetadata md = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(promptTokens, completionTokens)).build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), md);
    }

    private static StreamingChatModelCandidate candidate(String name, String model,
                                                         boolean available,
                                                         Flux<ChatResponse> flux) {
        return new StreamingChatModelCandidate() {
            @Override public String name() { return name; }
            @Override public String model() { return model; }
            @Override public boolean available() { return available; }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { return flux; }
        };
    }

    private final Prompt prompt = new Prompt("hi");

    @Test
    void 首候选首包前失败切次候选并写fallbackFrom日志() {
        StreamingChatModelCandidate bad = candidate("dashscope:qwen-plus", "qwen-plus", true,
                Flux.error(new RuntimeException("500")));
        StreamingChatModelCandidate good = candidate("dashscope:qwen-turbo", "qwen-turbo", true,
                Flux.just(chunk("你"), chunk("好")));
        ModelCallRecorder recorder = mock(ModelCallRecorder.class);
        StreamingChatChain chain = new StreamingChatChain(List.of(bad, good));
        chain.setRecorder(recorder);

        StringBuilder sb = new StringBuilder();
        chain.stream(prompt, "u1", "s1").doOnNext(r -> sb.append(r.getResult().getOutput().getText()))
                .blockLast();

        assertThat(sb.toString()).isEqualTo("你好");
        // 失败候选一行 ERROR；成功候选一行 OK 且 fallbackFrom 指向首候选
        verify(recorder).record(eq(ModelCallLogPurpose.CHAT), eq("dashscope:qwen-plus"),
                eq("qwen-plus"), isNull(), isNull(), anyLong(), eq(false), isNull(), eq("u1"), eq("s1"));
        verify(recorder).record(eq(ModelCallLogPurpose.CHAT), eq("dashscope:qwen-turbo"),
                eq("qwen-turbo"), eq(0), eq(0), anyLong(), eq(true),
                eq("dashscope:qwen-plus"), eq("u1"), eq("s1"));
    }

    @Test
    void 不可用候选直接跳过且fallbackFrom记其名() {
        StreamingChatModelCandidate unavailable = candidate("dashscope:qwen-plus", "qwen-plus", false,
                Flux.just(chunk("不应出现")));
        StreamingChatModelCandidate good = candidate("dashscope:qwen-turbo", "qwen-turbo", true,
                Flux.just(chunk("ok")));
        ModelCallRecorder recorder = mock(ModelCallRecorder.class);
        StreamingChatChain chain = new StreamingChatChain(List.of(unavailable, good));
        chain.setRecorder(recorder);

        String text = chain.stream(prompt).blockLast().getResult().getOutput().getText();
        assertThat(text).isEqualTo("ok");
        verify(recorder).record(eq(ModelCallLogPurpose.CHAT), eq("dashscope:qwen-turbo"),
                eq("qwen-turbo"), eq(0), eq(0), anyLong(), eq(true),
                eq("dashscope:qwen-plus"), isNull(), isNull());
        verifyNoMoreInteractions(recorder); // 跳过的不可用候选不产生 ERROR 行
    }

    @Test
    void usage跨帧累加() {
        StreamingChatModelCandidate c = candidate("p", "m", true, Flux.just(
                chunk("甲", 10, 3), chunk("乙", 10, 7)));
        ModelCallRecorder recorder = mock(ModelCallRecorder.class);
        StreamingChatChain chain = new StreamingChatChain(List.of(c));
        chain.setRecorder(recorder);

        chain.stream(prompt).blockLast();
        verify(recorder).record(eq(ModelCallLogPurpose.CHAT), eq("p"), eq("m"),
                eq(20), eq(10), anyLong(), eq(true), isNull(), isNull(), isNull());
    }

    @Test
    void 首包后失败不切换而传播错误并写ERROR行() {
        StreamingChatModelCandidate c = candidate("p", "m", true,
                Flux.concat(Flux.just(chunk("已输出")), Flux.error(new RuntimeException("中途断流"))));
        StreamingChatModelCandidate backup = candidate("b", "m2", true, Flux.just(chunk("兜底")));
        ModelCallRecorder recorder = mock(ModelCallRecorder.class);
        StreamingChatChain chain = new StreamingChatChain(List.of(c, backup));
        chain.setRecorder(recorder);

        Throwable t = catchThrowable(() -> chain.stream(prompt).blockLast());
        assertThat(t).hasMessageContaining("中途断流");
        verify(recorder).record(eq(ModelCallLogPurpose.CHAT), eq("p"), eq("m"),
                isNull(), isNull(), anyLong(), eq(false), isNull(), isNull(), isNull());
        verifyNoMoreInteractions(recorder); // 未静默切到兜底候选重放
    }
}
