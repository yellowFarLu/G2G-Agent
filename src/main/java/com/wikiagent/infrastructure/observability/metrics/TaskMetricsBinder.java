package com.wikiagent.infrastructure.observability.metrics;

import com.wikiagent.domain.observability.TaskMetricsQueryPort;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 子项目 I（AC-I2）任务积压指标绑定器：{@code wikiagent_task_backlog}
 * （Gauge，tag status=PENDING/RUNNING/WAITING_HUMAN/FAILED）。
 * <p>
 * {@link #refresh()} 从只读端口刷内存计数（启动时立即刷一次，之后每 15s），
 * Prometheus 抓取不直接打 DB。刷新异常只告警不抛出（观测不影响主链路）。
 * <p>
 * 任务时长 {@code wikiagent_task_duration_seconds}、失败计数
 * {@code wikiagent_task_failed_total} 在 TaskWorker 完成/失败点直接记录。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.observability.enabled", havingValue = "true", matchIfMissing = true)
public class TaskMetricsBinder {

    private static final Logger log = LoggerFactory.getLogger(TaskMetricsBinder.class);

    /** 积压观测的状态集合（与 AC-I2 约定一致）。 */
    public static final String[] BACKLOG_STATUSES = {"PENDING", "RUNNING", "WAITING_HUMAN", "FAILED"};

    private final ObjectProvider<MeterRegistry> registryProvider;
    private final ObjectProvider<TaskMetricsQueryPort> queryProvider;
    private final Map<String, AtomicLong> backlog = new LinkedHashMap<>();

    public TaskMetricsBinder(ObjectProvider<MeterRegistry> registryProvider,
                             ObjectProvider<TaskMetricsQueryPort> queryProvider) {
        this.registryProvider = registryProvider;
        this.queryProvider = queryProvider;
        for (String status : BACKLOG_STATUSES) {
            backlog.put(status, new AtomicLong());
        }
    }

    @PostConstruct
    void register() {
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        for (String status : BACKLOG_STATUSES) {
            Gauge.builder("wikiagent.task.backlog", backlog.get(status), AtomicLong::doubleValue)
                    .tag("status", status)
                    .description("任务积压数（按状态）")
                    .register(registry);
        }
        refresh();
    }

    /** 定时刷新积压计数（默认 15s，可配 wikiagent.observability.task-backlog-interval-ms）。 */
    @Scheduled(fixedDelayString = "${wikiagent.observability.task-backlog-interval-ms:15000}")
    public void refresh() {
        TaskMetricsQueryPort query = queryProvider.getIfAvailable();
        if (query == null) {
            return;
        }
        for (String status : BACKLOG_STATUSES) {
            try {
                backlog.get(status).set(query.countByStatus(status));
            } catch (Exception e) {
                log.warn("任务积压指标刷新失败 status={}: {}", status, e.getMessage());
            }
        }
    }

    /** 测试辅助：读取某状态当前快照值。 */
    long backlog(String status) {
        AtomicLong v = backlog.get(status);
        return v == null ? -1 : v.get();
    }
}
