package com.wikiagent.infrastructure.memory.milvus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * v6 §22.5 历史事件库 Milvus 的并发写入幂等去重（§22.4 推荐：Redis SETNX + Milvus 唯一字段兜底）。
 * <p>
 * Milvus 无事务，§22.3 调研中没有方案直接适用；用幂等去重避免并发重复 insert：
 * <ol>
 *   <li>写入前先 Redis {@code SETNX evt:{eventId} 1 EX 5min}</li>
 *   <li>SETNX 成功 → 执行实际 Milvus 写入（{@code actualWrite} 回调）</li>
 *   <li>SETNX 失败 → 视为已写入，跳过（避免重复 insert）</li>
 * </ol>
 * <p>
 * <b>实施校正</b>（遵循"禁止捏造事实"约束）：
 * §22.5 骨架设想 {@code milvus.insert(HistoricalEvent e)}，但实测
 * {@link com.wikiagent.service.store.MilvusStoreService} 当前只提供
 * {@code insertChildren(List<KbChildChunk>, List<float[]>)}，没有 {@code HistoricalEvent} 类型
 * 也没有通用 {@code insert(HistoricalEvent)} 方法（§6 历史事件库未实施）。
 * 本类改为接收 {@code eventId} + {@code Runnable actualWrite}，由调用方注入实际写入逻辑，
 * 避免捏造不存在的 MilvusStoreService.insert(HistoricalEvent) 方法。
 * <p>
 * v3-v5 实施时由 §6 历史事件库实施者替换 actualWrite 为真实 Milvus insert 调用。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.redis.enabled", havingValue = "true")
public class MilvusEventSink {

    private static final Logger log = LoggerFactory.getLogger(MilvusEventSink.class);

    private static final String EVT_KEY_PREFIX = "evt:";

    private final StringRedisTemplate redis;
    private final long dedupWindowMinutes;

    public MilvusEventSink(StringRedisTemplate redis,
                           @Value("${wikiagent.concurrency.milvus.dedup-window-minutes:5}") long dedupWindowMinutes) {
        this.redis = redis;
        this.dedupWindowMinutes = dedupWindowMinutes;
    }

    /**
     * 幂等去重写入：Redis SETNX 成功才执行 actualWrite。
     *
     * @param eventId     事件唯一 ID（用于去重 key）
     * @param actualWrite 实际写入 Milvus 的回调（§6 实施时由调用方注入真实 insert 逻辑）
     * @return true 表示本次写入执行；false 表示已被其他线程/节点写入，跳过
     */
    public boolean upsert(String eventId, Runnable actualWrite) {
        Boolean acquired = redis.opsForValue().setIfAbsent(
                EVT_KEY_PREFIX + eventId, "1", Duration.ofMinutes(dedupWindowMinutes));
        if (Boolean.TRUE.equals(acquired)) {
            actualWrite.run();
            log.debug("MilvusEventSink 写入事件 {}（首次，SETNX 成功）", eventId);
            return true;
        }
        log.debug("MilvusEventSink 跳过事件 {}（已存在，SETNX 失败）", eventId);
        return false;
    }
}
