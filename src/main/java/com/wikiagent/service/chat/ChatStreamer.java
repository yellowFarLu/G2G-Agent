package com.wikiagent.service.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 流式回答：订阅 chatModel.stream，转发 delta 事件，客户端断开时停止生成。
 * 完成后发送 done 并结束 SSE；失败发送 error 并结束 SSE。
 */
@Component
public class ChatStreamer {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamer.class);

    private final ChatModel chatModel;

    public ChatStreamer(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public void stream(Prompt prompt, SseSender sse) {
        stream(prompt, sse, null);
    }

    /**
     * 流式回答。
     *
     * @param emptyNote 非 null 时，若整个流未产生任何文本（如 NoOpChatModel / 空响应），
     *                  在完成前补发该提示，避免前端出现空白回答
     */
    public void stream(Prompt prompt, SseSender sse, String emptyNote) {
        AtomicReference<Disposable> subRef = new AtomicReference<>();
        AtomicBoolean gotText = new AtomicBoolean(false);
        Disposable sub = chatModel.stream(prompt)
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
