package com.wikiagent.service.chat;

import com.wikiagent.application.llm.ModelCallRecorder;
import com.wikiagent.domain.llm.ModelCallLogPurpose;
import com.wikiagent.infrastructure.llm.StreamingChatChain;
import com.wikiagent.infrastructure.llm.StreamingChatModelCandidate;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺陷1 ChatStreamer 接线：SSE 流式完成后由降级链写 purpose=CHAT 日志；
 * 无链 Bean 时回退裸 chatModel（旧行为）。
 */
class ChatStreamerChainTest {

    private static StreamingChatModelCandidate okCandidate() {
        return new StreamingChatModelCandidate() {
            @Override public String name() { return "dashscope:qwen-plus"; }
            @Override public String model() { return "qwen-plus"; }
            @Override public boolean available() { return true; }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("你好")))));
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<StreamingChatChain> chainProvider(StreamingChatChain chain) {
        ObjectProvider<StreamingChatChain> op = mock(ObjectProvider.class);
        when(op.getIfAvailable()).thenReturn(chain);
        return op;
    }

    @Test
    void 走流式链时SSE完成并写CHAT日志() throws Exception {
        ModelCallRecorder recorder = mock(ModelCallRecorder.class);
        StreamingChatChain chain = new StreamingChatChain(List.of(okCandidate()));
        chain.setRecorder(recorder);

        SseEmitter emitter = mock(SseEmitter.class);
        ChatStreamer streamer = new ChatStreamer(mock(ChatModel.class), chainProvider(chain));

        streamer.stream(new Prompt("你好"), new SseSender(emitter), null, "u1", "s1");

        // delta + done 均经 SseEventBuilder 发送（该 builder 不暴露事件名，校验发送次数 ≥2），
        // 随后 complete；CHAT 日志由链在 onComplete 写入（见下）
        verify(emitter, org.mockito.Mockito.atLeast(2))
                .send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        verify(recorder).record(eq(ModelCallLogPurpose.CHAT), eq("dashscope:qwen-plus"),
                eq("qwen-plus"), eq(0), eq(0), anyLong(), eq(true),
                org.mockito.ArgumentMatchers.isNull(), eq("u1"), eq("s1"));
    }

    @Test
    void 无链Bean时回退裸chatModel且不写日志() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("直答"))))));
        SseEmitter emitter = mock(SseEmitter.class);

        new ChatStreamer(chatModel).stream(new Prompt("hi"), new SseSender(emitter));

        verify(chatModel).stream(any(Prompt.class));
        verify(emitter).complete();
    }
}
