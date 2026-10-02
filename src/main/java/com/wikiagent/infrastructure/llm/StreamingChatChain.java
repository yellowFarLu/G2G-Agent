package com.wikiagent.infrastructure.llm;

import com.wikiagent.application.llm.ModelCallRecorder;
import com.wikiagent.domain.llm.ModelCallLogPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流式聊天降级链 + CHAT 打点（同步 {@link ChatModelProviderChain} 无 stream 能力，
 * 流式的安全降级窗口只在"首个文本帧发出之前"）：
 * <ul>
 *   <li>候选不可用（如缺 API Key）直接跳过；非末端候选首包前 onError → 切换下一个候选，
 *       失败候选落一行 purpose=CHAT、status=ERROR 日志；</li>
 *   <li>已经产出过文本帧后 onError 不能静默重放（否则客户端看到重复/错乱内容），
 *       落 ERROR 行后把错误抛给上层（SSE error 事件）；</li>
 *   <li>服务候选正常 onComplete 时落 OK 行：provider/model 为实际候选，
 *       Usage 跨帧累加（取不到记 0），fallbackFrom 为首个失败/跳过候选名；</li>
 *   <li>全部候选首包前失败：传播最后一个错误。</li>
 * </ul>
 * 诚实声明：流式路径不复用同步链的重试/熔断器——流式调用中途无法安全重试，
 * 首包前切换已覆盖"服务不可用/鉴权失败"这类主要故障形态。
 */
public class StreamingChatChain {

    private static final Logger log = LoggerFactory.getLogger(StreamingChatChain.class);

    private final List<StreamingChatModelCandidate> candidates;
    private volatile ModelCallRecorder recorder;

    public StreamingChatChain(List<StreamingChatModelCandidate> candidates) {
        this.candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    /** 注入打点器（Bean 装配后调用一次）。 */
    public void setRecorder(ModelCallRecorder recorder) {
        this.recorder = recorder;
    }

    public Flux<ChatResponse> stream(Prompt prompt) {
        return stream(prompt, null, null);
    }

    public Flux<ChatResponse> stream(Prompt prompt, String userId, String sessionId) {
        return Flux.defer(() -> subscribeCandidate(prompt, userId, sessionId, 0, null));
    }

    private Flux<ChatResponse> subscribeCandidate(Prompt prompt, String userId, String sessionId,
                                                  int index, String firstFallback) {
        if (index >= candidates.size()) {
            return Flux.error(new IllegalStateException("流式聊天降级链所有候选均不可用/失败"));
        }
        StreamingChatModelCandidate candidate = candidates.get(index);
        boolean last = index == candidates.size() - 1;
        if (!candidate.available() && !last) {
            String ff = firstFallback == null ? candidate.name() : firstFallback;
            return subscribeCandidate(prompt, userId, sessionId, index + 1, ff);
        }
        long started = System.currentTimeMillis();
        String fallbackFrom = firstFallback;
        AtomicBoolean emitted = new AtomicBoolean(false);
        UsageAccumulator usage = new UsageAccumulator();

        return candidate.stream(prompt)
                .doOnNext(resp -> {
                    emitted.set(true);
                    usage.add(resp);
                })
                .doOnComplete(() -> record(candidate, System.currentTimeMillis() - started,
                        true, fallbackFrom, usage.promptTokens(), usage.completionTokens(),
                        userId, sessionId))
                .doOnError(err -> record(candidate, System.currentTimeMillis() - started,
                        false, null, null, null, userId, sessionId))
                .onErrorResume(err -> {
                    if (emitted.get()) {
                        return Flux.error(err); // 已有输出，重放会产生重复内容
                    }
                    log.warn("流式候选首包前失败，切换下一候选: {}: {}", candidate.name(), err.getMessage());
                    String ff = fallbackFrom == null ? candidate.name() : fallbackFrom;
                    return subscribeCandidate(prompt, userId, sessionId, index + 1, ff);
                });
    }

    private void record(StreamingChatModelCandidate candidate, long latencyMs, boolean success,
                        String fallbackFrom, Integer tokensIn, Integer tokensOut,
                        String userId, String sessionId) {
        ModelCallRecorder r = recorder;
        if (r != null) {
            r.record(ModelCallLogPurpose.CHAT, candidate.name(), candidate.model(),
                    tokensIn, tokensOut, latencyMs, success, fallbackFrom, userId, sessionId);
        }
    }

    /** 跨帧累加 Usage；全程取不到用量时 token 记 0（任务书约定，避免 null 语义歧义）。 */
    private static final class UsageAccumulator {
        private int promptTokens;
        private int completionTokens;
        private boolean seen;

        void add(ChatResponse resp) {
            try {
                if (resp == null || resp.getMetadata() == null || resp.getMetadata().getUsage() == null) {
                    return;
                }
                Usage u = resp.getMetadata().getUsage();
                if (u.getPromptTokens() != null) {
                    promptTokens += u.getPromptTokens();
                }
                if (u.getCompletionTokens() != null) {
                    completionTokens += u.getCompletionTokens();
                }
                seen = true;
            } catch (Exception ignore) {
                // 用量读取不影响主链路
            }
        }

        Integer promptTokens() {
            return seen ? promptTokens : 0;
        }

        Integer completionTokens() {
            return seen ? completionTokens : 0;
        }
    }
}
