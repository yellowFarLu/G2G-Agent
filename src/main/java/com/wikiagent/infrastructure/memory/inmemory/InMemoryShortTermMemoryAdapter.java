package com.wikiagent.infrastructure.memory.inmemory;

import com.wikiagent.domain.memory.ShortTermMemoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * v1-v2 §3 实施校正：{@link ShortTermMemoryPort} 的进程内降级实现。
 * <p>
 * 当 {@code wikiagent.redis.enabled=false}（开发默认，Redis 不可用）时替代
 * {@code RedisShortTermMemoryAdapter}，保证 Agent 主循环短期记忆链路完整可演示。
 * <p>
 * 语义对齐 Redis 实现：
 * <ul>
 *   <li>key 为 {@code userId:sessionId}，按追加顺序保存</li>
 *   <li>超过 {@code wikiagent.memory.short-term-max-turns}（默认 20，硬约束上限）滚动丢弃最旧消息</li>
 * </ul>
 * <p>
 * <b>诚实声明</b>：数据仅存于当前 JVM 堆内存，重启丢失、不跨实例共享、无 TTL。
 * 生产部署必须设 {@code REDIS_ENABLED=true}（§3 短期记忆存储于 Redis 的硬约束）。
 */
@Service
@ConditionalOnProperty(name = "wikiagent.redis.enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryShortTermMemoryAdapter implements ShortTermMemoryPort {

    private static final Logger log = LoggerFactory.getLogger(InMemoryShortTermMemoryAdapter.class);

    private final ConcurrentMap<String, Deque<MessageEntry>> store = new ConcurrentHashMap<>();
    private final int maxTurns;

    public InMemoryShortTermMemoryAdapter(
            @Value("${wikiagent.memory.short-term-max-turns:20}") int maxTurns) {
        this.maxTurns = Math.max(1, maxTurns);
    }

    @Override
    public void save(String userId, String sessionId, String role, String content) {
        if (userId == null || sessionId == null || role == null) {
            return;
        }
        Deque<MessageEntry> deque = store.computeIfAbsent(key(userId, sessionId), k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(new MessageEntry(role, content == null ? "" : content, System.currentTimeMillis()));
            while (deque.size() > maxTurns) {
                deque.removeFirst();
            }
        }
    }

    @Override
    public List<MessageEntry> load(String userId, String sessionId, int maxTurns) {
        int limit = Math.max(1, Math.min(maxTurns, this.maxTurns));
        Deque<MessageEntry> deque = store.get(key(userId, sessionId));
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            int skip = Math.max(0, deque.size() - limit);
            List<MessageEntry> out = new ArrayList<>(limit);
            int idx = 0;
            for (MessageEntry e : deque) {
                if (idx++ >= skip) {
                    out.add(e);
                }
            }
            return out;
        }
    }

    @Override
    public List<MessageEntry> loadAll(String userId, String sessionId) {
        return load(userId, sessionId, this.maxTurns);
    }

    @Override
    public void clear(String userId, String sessionId) {
        Deque<MessageEntry> deque = store.remove(key(userId, sessionId));
        if (deque != null) {
            synchronized (deque) {
                deque.clear();
            }
        }
    }

    private static String key(String userId, String sessionId) {
        return userId + ":" + sessionId;
    }
}
