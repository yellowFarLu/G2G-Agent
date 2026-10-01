package com.wikiagent.application.task;

import com.wikiagent.config.TaskProperties;
import com.wikiagent.domain.task.TaskInstance;
import com.wikiagent.domain.task.ports.TaskDispatcherPort;
import com.wikiagent.domain.task.ports.TaskRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Task 7 outbox 投递补偿：扫描滞留 PENDING 任务重投（attempt 不增加，至少一次投递语义）。
 * 滞留判定：PENDING 且 next_run_at<=now 且 enqueue_at 为空或早于 now-stuckAge（stuckAge=扫描周期）。
 * dispatch 成功后 markEnqueued；同租户 RUNNING 数达上限时跳过等下轮。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.task.enabled", havingValue = "true")
public class DispatchCompensationJob {

    private static final Logger log = LoggerFactory.getLogger(DispatchCompensationJob.class);

    private static final int SCAN_LIMIT = 50;

    private final TaskRepositoryPort taskRepository;
    private final TaskDispatcherPort dispatcher;
    private final TaskProperties properties;

    public DispatchCompensationJob(TaskRepositoryPort taskRepository,
                                   TaskDispatcherPort dispatcher,
                                   TaskProperties properties) {
        this.taskRepository = taskRepository;
        this.dispatcher = dispatcher;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${wikiagent.task.dispatch-retry-scan-sec:5}000")
    public void runOnce() {
        Duration stuckAge = Duration.ofSeconds(Math.max(properties.getDispatchRetryScanSec(), 1));
        List<TaskInstance> candidates = taskRepository.findDispatchable(stuckAge, SCAN_LIMIT);
        for (TaskInstance task : candidates) {
            if (properties.getTenantMaxConcurrent() > 0 && task.tenantId() != null
                    && taskRepository.countRunningByTenant(task.tenantId()) >= properties.getTenantMaxConcurrent()) {
                log.debug("租户满载跳过本轮投递 taskId={} tenant={}", task.taskId(), task.tenantId());
                continue;
            }
            try {
                dispatcher.dispatch(task.taskId(), task.taskType(), 0);
                taskRepository.markEnqueued(task.taskId(), Instant.now());
            } catch (Exception e) {
                log.warn("补偿投递失败 taskId={}: {}（下一轮重试）", task.taskId(), e.getMessage());
            }
        }
    }
}
