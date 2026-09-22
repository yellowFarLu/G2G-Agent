package com.wikiagent.infrastructure.lock;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * v6 §22.5 统一并发抽象端口（Handoff Lock）。
 * <p>
 * §22.4 推荐组合采用"按资源特性分层"原则：结构化数据走 DB 原生锁，缓存走 Redis 锁，
 * 向量库走幂等去重，状态走 reducer——避免引入 CRDT/Actor 重迁移成本。
 * 本接口抽象 §22.3 #1 Redisson 分布式锁，作为跨节点互斥的兜底方案。
 * <p>
 * 实现类：
 * <ul>
 *   <li>{@link RedissonHandoffLock} — Redisson RLock（生产推荐，跨节点）</li>
 *   <li>v3-v5 实施时可选 {@code NoOpHandoffLock}（{@code wikiagent.concurrency.lock.provider=none} 时启用）</li>
 * </ul>
 * 与 §22.5 骨架完全一致。
 */
public interface HandoffLock {

    /**
     * 在锁保护下执行 action。
     * <p>
     * 行为契约：
     * <ul>
     *   <li>获取锁失败立即抛 {@link LockBusyException}（不阻塞等待）</li>
     *   <li>获取成功执行 action 并返回结果</li>
     *   <li>finally 块释放锁（仅当当前线程持有）</li>
     * </ul>
     *
     * @param key    锁 key（实现内部加 "lock:" 前缀）
     * @param ttl    锁过期时间（防进程崩溃后死锁）
     * @param action 受保护动作
     * @return action 返回值
     * @throws LockBusyException 锁已被其他线程/节点持有时
     */
    <T> T withLock(String key, Duration ttl, Supplier<T> action);
}
