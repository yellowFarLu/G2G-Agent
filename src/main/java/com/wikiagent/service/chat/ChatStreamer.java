package com.wikiagent.service.chat;

import com.wikiagent.infrastructure.llm.StreamingChatChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 流式回答：转发 delta 事件，客户端断开时停止生成。
 * 完成后发送 done 并结束 SSE；失败发送 error 并结束 SSE。
 * <p>
 * E4/缺陷1：存在 {@link StreamingChatChain} Bean 时走降级链（首包前失败自动切候选），
 * 由链写 purpose=CHAT 的 model_call_log（成功 OK / 失败 ERROR / fallbackFrom）；
 * 无链 Bean（旧装配/单测）时回退裸 chatModel.stream，行为与旧版一致。
 */
@Component
public class ChatStreamer {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamer.class);

    private final ChatModel chatModel;
    /** 缺陷1：可选流式降级链（打点 + 首包前候选切换）。 */
    private final StreamingChatChain streamChain;

    /** 兼容旧装配（既有测试直接 new）：无降级链，裸调 chatModel。 */
    public ChatStreamer(ChatModel chatModel) {
        this(chatModel, null);
    }

    @Autowired
    public ChatStreamer(ChatModel chatModel, ObjectProvider<StreamingChatChain> streamChain) {
        this.chatModel = chatModel;
        this.streamChain = streamChain == null ? null : streamChain.getIfAvailable();
    }

    public void stream(Prompt prompt, SseSender sse) {
        stream(prompt, sse, null, null, null);
    }

    /**
     * 流式回答。
     *
     * @param emptyNote 非 null 时，若整个流未产生任何文本（如 NoOpChatModel / 空响应），
     *                  在完成前补发该提示，避免前端出现空白回答
     */
    public void stream(Prompt prompt, SseSender sse, String emptyNote) {
        stream(prompt, sse, emptyNote, null, null);
    }

    /**
     * 带身份的流式回答（userId/sessionId 透传到 CHAT 打点；为 null 时打点身份列为空）。
     */
    public void stream(Prompt prompt, SseSender sse, String emptyNote,
                       String userId, String sessionId) {
        Flux<ChatResponse> source = streamChain != null
                ? streamChain.stream(prompt, userId, sessionId)
                : chatModel.stream(prompt);
        subscribe(prompt, sse, emptyNote, source);
    }

    private void subscribe(Prompt prompt, SseSender sse, String emptyNote,
                           Flux<ChatResponse> source) {
        AtomicReference<Disposable> subRef = new AtomicReference<>();
        AtomicBoolean gotText = new AtomicBoolean(false);
        Disposable sub = source
                .map(ChatStreamer::textOf)
                .filter(t -> t != null && !t.isEmpty())
                .subscribe(
                        delta -> {
                            gotText.set(true);
                            if (!sse.send("delta", Map.of("text", delta))) {
                                Disposable d = subRef.get();
                                if (d != null) {
                                    d.dispose(); // 客户端已断开，停止生成
                                }
                            }
                        },
                        err -> {
                            log.error("生成失败: {}", err.getMessage());
                            sse.send("error", Map.of("message", "生成失败: " + messageOf(err)));
                            sse.complete();
                        },
                        () -> {
                            if (emptyNote != null && !gotText.get()) {
                                sse.send("delta", Map.of("text", emptyNote));
                            }
                            sse.send("done", Map.of());
                            sse.complete();
                        });
        subRef.set(sub);
    }

    private static String textOf(ChatResponse resp) {
        if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) {
            return null;
        }
        AssistantMessage out = resp.getResult().getOutput();
        return out.getText();
    }

    private static String messageOf(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
