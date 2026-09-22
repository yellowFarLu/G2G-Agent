package com.wikiagent.infrastructure.memory.inmemory;

import com.wikiagent.application.agent.pero.EpisodicMemory;
import com.wikiagent.domain.agent.Reflection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * v6 §20.5 实施校正：{@link EpisodicMemory} 的进程内降级实现。
 * <p>
 * 当 {@code wikiagent.redis.enabled=false}（开发默认）时替代
 * {@code RedisEpisodicMemoryAdapter}，保证 PERO Reflect→下一轮 ReAct hint
 * 链路在无 Redis 环境可运行。
 * <p>
 * 语义与 Redis 实现对齐：每 key 保留最近 50 条反思，{@link #recall} 取最近 5 条。
 * <p>
 * <b>诚实声明 / 已知限制</b>（与 RedisEpisodicMemoryAdapter 相同）：
 * {@link Reflection} 当前只携带 {@code stepId}，不携带 userId/intent，
 * 故 {@link #put} 以 {@code default:{stepId}} 为 key 落盘，
 * {@link #recall(userId, intent)} 以 {@code userId:intent} 为 key 查询，
 * 两者暂不能关联。跨会话复用闭环需后续扩展 Reflection（增加 userId/intent 字段）
 * 后才能完全打通；本降级实现不虚构该能力。重启数据丢失，生产必须使用 Redis 实现。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.redis.enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryEpisodicMemoryAdapter implements EpisodicMemory {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEpisodicMemoryAdapter.class);

    private static final int MAX_ENTRIES = 50;
    private static final int RECALL_LIMIT = 5;

    private final ConcurrentMap<String, Deque<Reflection>> store = new ConcurrentHashMap<>();

    @Override
    public void put(Reflection r) {
        if (r == null || r.text() == null || r.text().isBlank()) {
            return;
        }
        String key = "wikiagent:episodic:default:" + (r.stepId() == null ? "unknown" : r.stepId());
        Deque<Reflection> deque = store.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(r);
            while (deque.size() > MAX_ENTRIES) {
                deque.removeFirst();
            }
        }
    }

    @Override
    public List<Reflection> recall(String userId, String intent) {
        String key = "wikiagent:episodic:" + userId + ":" + intent;
        Deque<Reflection> deque = store.get(key);
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            int skip = Math.max(0, deque.size() - RECALL_LIMIT);
            List<Reflection> out = new ArrayList<>(RECALL_LIMIT);
            int idx = 0;
            for (Reflection r : deque) {
                if (idx++ >= skip) {
                    out.add(r);
                }
            }
            return out;
        }
    }
}
