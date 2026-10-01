package com.wikiagent.application.task;

import java.time.Duration;

/**
 * 重试退避映射（规格 §3.3：10s/30s/2min，最多 3 次）。
 * RocketMQ 延迟等级：10s=3、30s=4、2min=6。
 */
public final class Backoff {

    private Backoff() {
    }

    /** attempt（1 起）→ RocketMQ 延迟等级；非法或越界（非 1/2/3）一律取最高档 6。 */
    public static int delayLevelForAttempt(int attempt) {
        return switch (attempt) {
            case 1 -> 3;
            case 2 -> 4;
            default -> 6;
        };
    }

    /** attempt（1 起）→ 重试延迟；越界一律 2m。 */
    public static Duration durationForAttempt(int attempt) {
        return durationForLevel(delayLevelForAttempt(attempt));
    }

    /** RocketMQ 延迟等级 → 本地调度时长（3→10s、4→30s、6→2m，未知档取 2m）。 */
    public static Duration durationForLevel(int level) {
        return switch (level) {
            case 3 -> Duration.ofSeconds(10);
            case 4 -> Duration.ofSeconds(30);
            default -> Duration.ofMinutes(2);
        };
    }
}
