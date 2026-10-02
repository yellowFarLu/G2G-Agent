package com.wikiagent.infrastructure.observability.ratelimit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * 子项目 I（AC-I3）最简内存令牌桶（Bucket4j 风格，零新依赖）：
 * 桶容量 = 每分钟许可请求数；按实际经过时间线性补充（nanos 计时，避免固定窗口边界突刺）。
 * <p>
 * 每键一个 {@link AtomicReference} 无锁 CAS 自旋；适合单实例默认部署。
 * 多实例部署的分布式限流为可选项（Redis 令牌桶 Lua，见 RateLimitFilter javadoc）。
 */
public class SimpleTokenBucket {

    /** 一次尝试的结果：allowed=true 放行；否则 retryAfterSec 给出建议等待秒数。 */
    public record Decision(boolean allowed, int retryAfterSec) {
        static Decision allow() {
            return new Decision(true, 0);
        }
    }

    private record State(double tokens, long lastRefillNanos) {
    }

    private final int capacity;
    private final double refillPerNano;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<String, AtomicReference<State>> buckets = new ConcurrentHashMap<>();
    /** 键数软上限，超过触发空闲桶清扫。 */
    private final int maxKeys;

    public SimpleTokenBucket(int requestsPerMinute) {
        this(requestsPerMinute, System::nanoTime, 100_000);
    }

    SimpleTokenBucket(int requestsPerMinute, LongSupplier nanoClock, int maxKeys) {
        if (requestsPerMinute <= 0) {
            throw new IllegalArgumentException("requestsPerMinute 必须为正数");
        }
        this.capacity = requestsPerMinute;
        this.refillPerNano = requestsPerMinute / 60.0 / 1_000_000_000.0;
        this.nanoClock = nanoClock;
        this.maxKeys = maxKeys;
    }

    /** 当前桶内令牌数（测试/运维观测）。 */
    double availableTokens(String key) {
        AtomicReference<State> ref = buckets.get(key);
        if (ref == null) {
            return capacity;
        }
        State s = ref.get();
        return Math.min(capacity, s.tokens() + (nanoClock.getAsLong() - s.lastRefillNanos()) * refillPerNano);
    }

    /**
     * 尝试取 1 个令牌。
     *
     * @return 放行或拒绝（Retry-After 秒，至少 1s）
     */
    public Decision tryAcquire(String key) {
        long now = nanoClock.getAsLong();
        AtomicReference<State> ref = buckets.computeIfAbsent(key,
                k -> new AtomicReference<>(new State(capacity, now)));
        while (true) {
            State current = ref.get();
            double refilled = current.tokens()
                    + (now - current.lastRefillNanos()) * refillPerNano;
            double tokens = Math.min(capacity, refilled);
            if (tokens >= 1.0d) {
                State next = new State(tokens - 1.0d, now);
                if (ref.compareAndSet(current, next)) {
                    return Decision.allow();
                }
                continue;
            }
            // 令牌不足：等待补足 1 个所需秒数（向上取整，最少 1s）
            double waitSec = (1.0d - tokens) / (refillPerNano * 1_000_000_000.0);
            int retryAfter = Math.max(1, (int) Math.ceil(waitSec));
            return new Decision(false, retryAfter);
        }
    }

    /** 测试辅助：重置某键为满桶。 */
    void reset(String key) {
        buckets.put(key, new AtomicReference<>(new State(capacity, nanoClock.getAsLong())));
    }

    /** 当前键数（测试/运维观测）。 */
    int size() {
        return buckets.size();
    }

    /** 清扫已满令牌的空闲桶（令牌满说明距上次消耗至少一个补充周期，非热点键）。 */
    void sweepIdle() {
        long now = nanoClock.getAsLong();
        buckets.entrySet().removeIf(e -> {
            State s = e.getValue().get();
            return s.tokens() >= capacity
                    && (now - s.lastRefillNanos()) * refillPerNano >= 1.0d;
        });
    }

    /** 超上限时清扫（由过滤器在键增长路径调用）。 */
    void sweepIfNeeded() {
        if (buckets.size() > maxKeys) {
            sweepIdle();
        }
    }
}
