package com.wikiagent.domain.llm;

import java.util.List;

/**
 * 模型调用日志仓储端口（domain 层接口，由 infrastructure 实现）。
 */
public interface ModelCallLogRepository {

    ModelCallLog save(ModelCallLog log);

    List<ModelCallLog> findByTraceId(String traceId);

    long count();
}
