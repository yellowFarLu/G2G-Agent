package com.wikiagent.service.chat;

import com.wikiagent.service.retrieve.QueryRewriteService;
import com.wikiagent.service.retrieve.RetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对话编排（运行于 chatExecutor）：查询改写 → 混合检索 → 父文档上下文 → 流式生成。
 * 通过 SseEmitter 推送事件：stage / sources / delta / done / error。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    static final String NO_CONTEXT_MESSAGE =
            "知识库中未检索到与该问题相关的内容，暂时无法回答。请先上传相关文档，或换一种问法。";

    private final QueryRewriteService rewriter;
    private final RetrievalService retrieval;
    private final ChatModel chatModel;

    public ChatService(QueryRewriteService rewriter, RetrievalService retrieval, ChatModel chatModel) {
        this.rewriter = rewriter;
        this.retrieval = retrieval;
        this.chatModel = chatModel;
    }

    @Async("chatExecutor")
    public void chat(String question, SseEmitter emitter) {
        try {
            // 1. 查询改写
            send(emitter, "stage", Map.of("stage", "rewriting"));
            String rewritten = rewriter.rewrite(question);

            // 2. 混合检索 + 父文档替换
            send(emitter, "stage", Map.of("stage", "retrieving", "rewrittenQuery", rewritten));
            RetrievalService.RetrievalResult result = retrieval.retrieve(rewritten);
            send(emitter, "sources", result.sources());

            if (result.context().isEmpty()) {
                send(emitter, "delta", Map.of("text", NO_CONTEXT_MESSAGE));
                send(emitter, "done", Map.of());
                emitter.complete();
                return;
            }

            // 3. 组装 Prompt 并流式生成
            send(emitter, "stage", Map.of("stage", "generating"));
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(PromptComposer.SYSTEM),
                    new UserMessage(PromptComposer.user(result.context(), question))));

            StringBuilder answer = new StringBuilder();
            AtomicReference<Disposable> subRef = new AtomicReference<>();
            Disposable sub = chatModel.stream(prompt)
                    .map(ChatService::textOf)
                    .filter(t -> t != null && !t.isEmpty())
                    .subscribe(
                            delta -> {
                                answer.append(delta);
                                if (!send(emitter, "delta", Map.of("text", delta))) {
                                    Disposable d = subRef.get();
                                    if (d != null) {
                                        d.dispose(); // 客户端已断开，停止生成
                                    }
                                }
                            },
                            err -> {
                                log.error("生成失败: {}", err.getMessage());
                                send(emitter, "error", Map.of("message", "生成失败: " + messageOf(err)));
                                emitter.complete();
                            },
                            () -> {
                                send(emitter, "done", Map.of());
                                emitter.complete();
                            });
            subRef.set(sub);
        } catch (Exception e) {
            log.error("对话处理失败", e);
            send(emitter, "error", Map.of("message", "对话处理失败: " + messageOf(e)));
            emitter.complete();
        }
    }

    private static String textOf(org.springframework.ai.chat.model.ChatResponse resp) {
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

    /** 返回 false 表示客户端已断开等发送失败。 */
    private boolean send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
            return true;
        } catch (Exception e) {
            log.debug("SSE 发送失败（客户端可能已断开）: {}", e.getMessage());
            return false;
        }
    }
}
