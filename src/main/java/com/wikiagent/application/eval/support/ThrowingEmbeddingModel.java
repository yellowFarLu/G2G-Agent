package com.wikiagent.application.eval.support;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * 离线评测专用 EmbeddingModel：任何向量化调用都快速失败。
 * <p>
 * 目的不是 mock 一个向量服务，而是让 {@code RetrievalService} 的混合检索在第一步
 * （查询向量化）即进入 catch 分支，确定性地走<b>本地关键词降级</b>路径——
 * 从而保证离线评测零 Milvus/零真实 embedding API 依赖（规格 §H 铁律4）。
 * 不触网、不等待超时、无随机/时钟行为。
 */
public class ThrowingEmbeddingModel implements EmbeddingModel {

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        throw new IllegalStateException("eval 离线模式：embedding 服务禁用，强制走本地关键词降级");
    }

    @Override
    public float[] embed(String text) {
        throw new IllegalStateException("eval 离线模式：embedding 服务禁用，强制走本地关键词降级");
    }

    @Override
    public float[] embed(org.springframework.ai.document.Document document) {
        throw new IllegalStateException("eval 离线模式：embedding 服务禁用，强制走本地关键词降级");
    }
}
