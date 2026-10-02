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

    @Bean
    public DashScopeRerankProvider dashScopeRerankProvider(
            @Value("${wikiagent.rerank.model:gte-rerank}") String rerankModel,
            @Value("${wikiagent.rerank.enabled:false}") boolean rerankEnabled) {
        // 首批适配位：默认不可用（enabled=false），RetrievalService 对不可用 provider 原序放行
        return new DashScopeRerankProvider(rerankModel, rerankEnabled);
    }
}
