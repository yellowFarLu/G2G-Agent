package com.wikiagent.infrastructure.task.mq;

import com.wikiagent.application.task.TaskMessageSink;
import com.wikiagent.config.TaskProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 本地调度投递器（wikiagent.task.enabled=true 且 mq=local，开发默认）。
 * 按 taskType 两个调度池（池大小取 concurrency），delayLevel 用
 * {@link com.wikiagent.application.task.Backoff} 换算；看门狗延迟 = leaseTtlSec（忽略延迟等级参数）。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.task.enabled", havingValue = "true")
@Conditional(TaskMqConditions.MqLocal.class)
public class LocalTaskDispatcher implements com.wikiagent.domain.task.ports.TaskDispatcherPort {

    private static final Logger log = LoggerFactory.getLogger(LocalTaskDispatcher.class);

    private final TaskProperties properties;
    private final ObjectProvider<TaskMessageSink> sinkProvider;
    private ThreadPoolTaskScheduler ingestScheduler;
    private ThreadPoolTaskScheduler agentScheduler;

    @Autowired
    public LocalTaskDispatcher(TaskProperties properties, ObjectProvider<TaskMessageSink> sinkProvider) {
        this.properties = properties;
        this.sinkProvider = sinkProvider;
    }

    /** 直连 sink 的便利构造（测试/单机内嵌用）。 */
    public LocalTaskDispatcher(TaskProperties properties, TaskMessageSink sink) {
        this.properties = properties;
        this.sinkProvider = new ObjectProvider<>() {
            @Override
            public TaskMessageSink getIfAvailable() {
                return sink;
            }

            @Override
            public TaskMessageSink getObject(Object... args) {
                return sink;
            }
        };
    }

    @PostConstruct
    public void start() {
        ingestScheduler = scheduler(properties.getConcurrency().getIngest(), "task-local-ingest-");
        agentScheduler = scheduler(properties.getConcurrency().getAgent(), "task-local-agent-");
    }

    @PreDestroy
    public void stop() {
        if (ingestScheduler != null) {
            ingestScheduler.shutdown();
        }
        if (agentScheduler != null) {
            agentScheduler.shutdown();
        }
    }

    private ThreadPoolTaskScheduler scheduler(int poolSize, String prefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(poolSize, 1));
        scheduler.setThreadNamePrefix(prefix);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }

    /** 延迟换算（测试可覆盖以缩短等待）。 */
    protected Duration delayForLevel(int level) {
        return com.wikiagent.application.task.Backoff.durationForLevel(level);
    }

    @Override
    public void dispatch(String taskId, String taskType, int delayLevel) {
        ThreadPoolTaskScheduler pool = "INGEST".equals(taskType) ? ingestScheduler : agentScheduler;
        pool.schedule(() -> {
            TaskMessageSink sink = sinkOrNull();
            if (sink != null) {
                sink.onMessage(taskId, taskType);
            }
        }, Instant.now().plus(delayForLevel(delayLevel)));
    }

    @Override
    public void dispatchWatchdog(String taskId, String ownerWorkerId, int delayLevel) {
        agentScheduler.schedule(() -> {
            TaskMessageSink sink = sinkOrNull();
            if (sink != null) {
                sink.onWatchdog(taskId, ownerWorkerId);
            }
        }, Instant.now().plusSeconds(properties.getLeaseTtlSec()));
    }

    private TaskMessageSink sinkOrNull() {
        TaskMessageSink sink = sinkProvider.getIfAvailable();
        if (sink == null) {
            log.warn("TaskMessageSink 未装配，消息跳过 taskId 丢弃（TaskWorker 缺失或 task.enabled=false）");
            return null;
        }
        return sink;
    }
}
