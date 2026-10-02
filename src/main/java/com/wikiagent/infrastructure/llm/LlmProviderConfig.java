package com.wikiagent.infrastructure.llm;

import com.wikiagent.application.llm.ModelCallRecorder;
import com.wikiagent.domain.llm.spi.ChatModelProvider;
import jakarta.annotation.PostConstruct;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * E2 LLM Provider 装配：把既有 DashScopeMultiModelFactory 的 ChatModel Bean
 * 包装为 {@link ChatModelProvider} 候选，组装降级链（末端追加 NoOp 兜底）。
 */
@Configuration
public class LlmProviderConfig {

    @Bean
    public DashScopeChatModelProvider dashScopeSimpleChatModelProvider(
            @Qualifier("simpleChatModel") ChatModel simpleChatModel,
            @Value("${wikiagent.routing.simple-model:qwen-plus}") String simpleModel) {
        return new DashScopeChatModelProvider("dashscope:" + simpleModel, simpleModel, simpleChatModel);
    }

    @Bean
    public ChatModelProviderChain chatModelProviderChain(
            List<ChatModelProvider> providers,
            @Value("${wikiagent.routing.simple-model:qwen-plus}") String simpleModel,
            @Value("${wikiagent.llm.max-attempts:2}") int maxAttempts,
            @Value("${wikiagent.llm.retry-backoff-ms:300}") long retryBackoffMs,
            @Value("${wikiagent.llm.circuit-failure-threshold:3}") int circuitFailureThreshold,
            @Value("${wikiagent.llm.circuit-open-sec:60}") long circuitOpenSec,
            ModelCallRecorder recorder) {
        List<ChatModelProvider> candidates = new ArrayList<>(providers);
        candidates.add(new NoOpChatModelProvider(simpleModel)); // 降级末端
        ChatModelProviderChain chain = new ChatModelProviderChain(candidates, maxAttempts, retryBackoffMs,
                circuitFailureThreshold, circuitOpenSec);
        chain.setRecorder(recorder);
        return chain;
    }

    /**
     * 流式聊天降级链：候选为所有 {@link StreamingChatModelCandidate} Bean
     * （DashScope 适配同时实现同步 SPI 与流式接口），末端追加 NoOp 空流兜底。
     */
    @Bean
    public StreamingChatChain streamingChatChain(
            List<StreamingChatModelCandidate> streamCandidates,
            @Value("${wikiagent.routing.simple-model:qwen-plus}") String simpleModel,
            ModelCallRecorder recorder) {
        List<StreamingChatModelCandidate> candidates = new ArrayList<>(streamCandidates);
        candidates.add(new NoOpChatModelProvider(simpleModel)); // 降级末端：空 Flux 不抛异常
        StreamingChatChain chain = new StreamingChatChain(candidates);
        chain.setRecorder(recorder);
        return chain;
    }

    @Bean
    public DashScopeRerankProvider dashScopeRerankProvider(
            @Value("${spring.ai.dashscope.api-key:}") String apiKey,
            @Value("${wikiagent.rerank.model:gte-rerank}") String rerankModel,
            @Value("${wikiagent.rerank.enabled:false}") boolean rerankEnabled,
            @Value("${wikiagent.rerank.base-url:" + DashScopeRerankProvider.DEFAULT_BASE_URL + "}") String baseUrl) {
        // 默认 enabled=false 或 API Key 缺失 → available()=false，RetrievalService 原序放行
        return new DashScopeRerankProvider(apiKey, rerankEnabled, baseUrl, rerankModel);
    }
}
