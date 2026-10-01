package com.wikiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 子项目 A 任务框架配置项（{@code wikiagent.task}）。
 *
 * <p>默认值与设计规格第 6 节 yaml 逐字对应：框架默认开启（{@link #enabled}=true），
 * {@link #mq}=local 使用 JVM 本地调度，开发环境零外部中间件依赖；
 * docker/生产环境通过 {@code TASK_MQ=rocketmq} 切换 RocketMQ 投递。</p>
 */
@ConfigurationProperties(prefix = "wikiagent.task")
public class TaskProperties {

    /** 任务框架总开关；false 时退回现有同步链路，任务 API 返回 503。 */
    private boolean enabled = true;

    /** 投递方式：local（JVM 本地调度，默认）| rocketmq。 */
    private String mq = "local";

    /** 各任务类型消费组并发度。 */
    private Concurrency concurrency = new Concurrency();

    /** Worker 租约 TTL（秒）。 */
    private int leaseTtlSec = 30;

    /** 心跳续租间隔（秒）。 */
    private int heartbeatSec = 10;

    /** 崩溃租约回收扫描周期（秒）。 */
    private int recoveryScanSec = 15;

    /** PENDING 滞留补偿扫描周期（秒）。 */
    private int dispatchRetryScanSec = 5;

    /** 同租户并发上限；0 表示不限。 */
    private int tenantMaxConcurrent = 0;

    /** 重试/超时默认值。 */
    private Defaults defaults = new Defaults();

    /** 业务重试指数退避档位：10s / 30s / 2min（RocketMQ 延迟等级 3/4/6）。 */
    private List<Duration> backoff = new ArrayList<>(List.of(
            Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(2)));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMq() {
        return mq;
    }

    public void setMq(String mq) {
        this.mq = mq;
    }

    public Concurrency getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(Concurrency concurrency) {
        this.concurrency = concurrency;
    }

    public int getLeaseTtlSec() {
        return leaseTtlSec;
    }

    public void setLeaseTtlSec(int leaseTtlSec) {
        this.leaseTtlSec = leaseTtlSec;
    }

    public int getHeartbeatSec() {
        return heartbeatSec;
    }

    public void setHeartbeatSec(int heartbeatSec) {
        this.heartbeatSec = heartbeatSec;
    }

    public int getRecoveryScanSec() {
        return recoveryScanSec;
    }

    public void setRecoveryScanSec(int recoveryScanSec) {
        this.recoveryScanSec = recoveryScanSec;
    }

    public int getDispatchRetryScanSec() {
        return dispatchRetryScanSec;
    }

    public void setDispatchRetryScanSec(int dispatchRetryScanSec) {
        this.dispatchRetryScanSec = dispatchRetryScanSec;
    }

    public int getTenantMaxConcurrent() {
        return tenantMaxConcurrent;
    }

    public void setTenantMaxConcurrent(int tenantMaxConcurrent) {
        this.tenantMaxConcurrent = tenantMaxConcurrent;
    }

    public Defaults getDefaults() {
        return defaults;
    }

    public void setDefaults(Defaults defaults) {
        this.defaults = defaults;
    }

    public List<Duration> getBackoff() {
        return backoff;
    }

    public void setBackoff(List<Duration> backoff) {
        this.backoff = backoff;
    }

    /** 各任务类型消费并发度，字段名对应规格 yaml {@code wikiagent.task.concurrency}。 */
    public static class Concurrency {

        /** 文档入库消费组 cg-task-ingest 并发度。 */
        private int ingest = 2;

        /** Agent 任务消费组 cg-task-agent 并发度。 */
        private int agent = 4;

        public int getIngest() {
            return ingest;
        }

        public void setIngest(int ingest) {
            this.ingest = ingest;
        }

        public int getAgent() {
            return agent;
        }

        public void setAgent(int agent) {
            this.agent = agent;
        }
    }

    /** 任务重试与超时默认值，字段名对应规格 yaml {@code wikiagent.task.defaults}。 */
    public static class Defaults {

        /** 任务级重试上限。 */
        private int maxAttempts = 3;

        /** 单步骤默认超时（秒）。 */
        private int stepTimeoutSec = 300;

        /** Agent 单步默认超时（秒）。 */
        private int agentStepTimeoutSec = 180;

        /** 任务级 deadline（秒）。 */
        private int deadlineSec = 1800;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public int getStepTimeoutSec() {
            return stepTimeoutSec;
        }

        public void setStepTimeoutSec(int stepTimeoutSec) {
            this.stepTimeoutSec = stepTimeoutSec;
        }

        public int getAgentStepTimeoutSec() {
            return agentStepTimeoutSec;
        }

        public void setAgentStepTimeoutSec(int agentStepTimeoutSec) {
            this.agentStepTimeoutSec = agentStepTimeoutSec;
        }

        public int getDeadlineSec() {
            return deadlineSec;
        }

        public void setDeadlineSec(int deadlineSec) {
            this.deadlineSec = deadlineSec;
        }
    }
}
