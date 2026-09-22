package com.wikiagent.infrastructure.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * DashScope 降级配置（实施校正）。
 * <p>
 * 实测 spring-ai-alibaba 1.1.2.0 的 {@code DashScopeEmbeddingAutoConfiguration}
 * 在 Bean 实例化阶段硬校验 API Key（{@code DashScope API key must be set}），
 * Key 缺失时整个 ApplicationContext 启动失败。为保持"开发环境无 Key 也可启动"约束：
 * <ol>
 *   <li>{@code ConditionalInfraEnvironmentPostProcessor} 在 Key 为空时排除该自动装配，
 *       并打标记 {@code wikiagent.internal.embedding-noop=true}</li>
 *   <li>本配置据标记装配 {@link NoOpEmbeddingModel}，向量检索链路 Bean 依赖完整</li>
 * </ol>
 * <b>诚实声明</b>：NoOp 返回全 0 向量，仅保证启动与流程贯通，不具备真实语义检索能力；
 * 配置 {@code DASHSCOPE_API_KEY} 后自动切换为真实 DashScope Embedding。
 */
@Configuration
@ConditionalOnClass(name = "org.springframework.ai.embedding.EmbeddingModel")
public class DashScopeFallbackConfig {

    @Bean
    @ConditionalOnProperty(name = "wikiagent.internal.embedding-noop", havingValue = "true")
    public EmbeddingModel noOpEmbeddingModel(
            @Value("${spring.ai.dashscope.embedding.options.dimensions:1024}") int dimensions) {
        return new NoOpEmbeddingModel(Math.max(1, dimensions));
    }

    /** 全 0 向量 EmbeddingModel，API Key 缺失时的启动占位实现。 */
    static final class NoOpEmbeddingModel implements EmbeddingModel {

        private static final Logger log = LoggerFactory.getLogger(NoOpEmbeddingModel.class);

        private final int dimensions;

        NoOpEmbeddingModel(int dimensions) {
            this.dimensions = dimensions;
            log.warn("DASHSCOPE_API_KEY 为空，EmbeddingModel 退化为 NoOp（{} 维全 0 向量），语义检索不可用",
                    dimensions);
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            int size = request.getInstructions() == null ? 0 : request.getInstructions().size();
            List<Embedding> results = new ArrayList<>(Math.max(size, 0));
            for (int i = 0; i < size; i++) {
                results.add(new Embedding(zeroVector(), i));
            }
            return new EmbeddingResponse(results);
        }

        @Override
        public float[] embed(Document document) {
            return zeroVector();
        }

        @Override
        public int dimensions() {
            return dimensions;
        }

        private float[] zeroVector() {
            return new float[dimensions];
        }
    }
}
