package com.wikiagent.infrastructure.task.jvm;

import com.wikiagent.application.task.OptimisticControlConflictException;
import com.wikiagent.domain.task.ControlFlag;
import com.wikiagent.domain.task.ports.ControlFlagPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 控制标志端口 JVM 降级实现（wikiagent.redis.enabled=false，开发默认）。
 * 进程内 Hash（flag/version），compute 原子校验 expectedVersion，不匹配抛乐观冲突。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.redis.enabled", havingValue = "false", matchIfMissing = true)
public class JvmControlFlagPort implements ControlFlagPort {

    private static final class FlagEntry {
        final ControlFlag flag;
        final int version;

        FlagEntry(ControlFlag flag, int version) {
            this.flag = flag;
            this.version = version;
        }
    }

    private final ConcurrentHashMap<String, FlagEntry> flags = new ConcurrentHashMap<>();

    @Override
    public void requestPause(String taskId, int expectedVersion) {
        request(taskId, expectedVersion, ControlFlag.PAUSE);
    }

    @Override
    public void requestCancel(String taskId, int expectedVersion) {
        request(taskId, expectedVersion, ControlFlag.CANCEL);
    }

    private void request(String taskId, int expectedVersion, ControlFlag flag) {
        flags.compute(taskId, (k, current) -> {
            int currentVersion = current == null ? 0 : current.version;
            if (currentVersion != expectedVersion) {
                throw new OptimisticControlConflictException(
                        "控制版本冲突 taskId=%s expected=%d actual=%d".formatted(taskId, expectedVersion, currentVersion));
            }
            return new FlagEntry(flag, currentVersion + 1);
        });
    }

    @Override
    public ControlFlag read(String taskId) {
        FlagEntry entry = flags.get(taskId);
        return entry == null ? ControlFlag.NONE : entry.flag;
    }

    @Override
    public int currentVersion(String taskId) {
        FlagEntry entry = flags.get(taskId);
        return entry == null ? 0 : entry.version;
    }

    @Override
    public void clear(String taskId) {
        flags.remove(taskId);
    }
}
