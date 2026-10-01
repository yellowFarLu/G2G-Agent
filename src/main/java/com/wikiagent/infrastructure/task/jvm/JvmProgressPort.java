package com.wikiagent.infrastructure.task.jvm;

import com.wikiagent.domain.task.ports.ProgressPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 进度端口 JVM 降级实现（wikiagent.redis.enabled=false，开发默认）：进程内最新进度。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.redis.enabled", havingValue = "false", matchIfMissing = true)
public class JvmProgressPort implements ProgressPort {

    public record Progress(int percent, String currentStep) {
    }

    private final ConcurrentHashMap<String, Progress> progress = new ConcurrentHashMap<>();

    @Override
    public void publish(String taskId, int percent, String currentStep) {
        progress.put(taskId, new Progress(percent, currentStep));
    }

    /** 读取最新进度（观测/测试用，端口契约外方法）。 */
    public Progress latest(String taskId) {
        return progress.get(taskId);
    }
}
