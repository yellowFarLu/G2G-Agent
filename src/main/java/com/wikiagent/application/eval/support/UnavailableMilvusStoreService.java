package com.wikiagent.application.eval.support;

import com.wikiagent.config.WikiAgentProperties;
import com.wikiagent.service.store.MilvusStoreService;

import java.util.List;

/**
 * 离线评测专用 Milvus 端口：构造零副作用（不创建客户端），hybridSearch 立即抛错，
 * 使 {@code RetrievalService} 进入本地关键词降级并进入 60s 冷却（评测进程生命周期内只抛一次）。
 * <p>
 * 不连接任何 Milvus 实例（规格 §H 铁律4：零 Docker/零外部依赖）。
 */
public class UnavailableMilvusStoreService extends MilvusStoreService {

    public UnavailableMilvusStoreService(WikiAgentProperties props) {
        super(props);
    }

    @Override
    public List<Hit> hybridSearch(float[] queryEmbedding, String rewrittenQuery,
                                  int subTopk, int finalTopk, int rrfK, String extraExpr) {
        throw new IllegalStateException("eval 离线模式：Milvus 禁用，强制走本地关键词降级");
    }
}
