package com.wikiagent.infrastructure.persistence.llm;

import com.wikiagent.domain.llm.ModelCallLog;
import com.wikiagent.domain.llm.ModelCallLogPurpose;
import com.wikiagent.domain.llm.ModelCallLogRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * 模型调用日志仓储 JPA 实现。
 */
@Repository
public class ModelCallLogRepositoryImpl implements ModelCallLogRepository {

    private final ModelCallLogJpaDao dao;

    public ModelCallLogRepositoryImpl(ModelCallLogJpaDao dao) {
        this.dao = dao;
    }

    @Override
    public ModelCallLog save(ModelCallLog log) {
        return toDomain(dao.save(toEntity(log)));
    }

    @Override
    public List<ModelCallLog> findByTraceId(String traceId) {
        return dao.findByTraceId(traceId).stream().map(this::toDomain).toList();
    }

    @Override
    public long count() {
        return dao.count();
    }

    private ModelCallLog toDomain(ModelCallLogEntity e) {
        return new ModelCallLog(e.getId(), e.getTraceId(), e.getUserId(), e.getSessionId(),
                ModelCallLogPurpose.from(e.getPurpose()), e.getProvider(), e.getModel(),
                e.getTokensIn(), e.getTokensOut(), e.getCostEstimate(), e.getLatencyMs(),
                ModelCallLog.Status.from(e.getStatus()), e.getFallbackFrom(), e.getCreatedAt());
    }

    private ModelCallLogEntity toEntity(ModelCallLog l) {
        ModelCallLogEntity e = new ModelCallLogEntity();
        e.setId(l.id());
        e.setTraceId(l.traceId());
        e.setUserId(l.userId());
        e.setSessionId(l.sessionId());
        e.setPurpose(l.purpose() == null ? null : l.purpose().name());
        e.setProvider(l.provider());
        e.setModel(l.model());
        e.setTokensIn(l.tokensIn());
        e.setTokensOut(l.tokensOut());
        e.setCostEstimate(l.costEstimate());
        e.setLatencyMs(l.latencyMs());
        e.setStatus(l.status() == null ? "OK" : l.status().name());
        e.setFallbackFrom(l.fallbackFrom());
        e.setCreatedAt(l.createdAt() == null ? Instant.now() : l.createdAt());
        return e;
    }
}
