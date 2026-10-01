package com.wikiagent.domain.task.ports;

/**
 * 投递端口：RocketMQ 实现与本地调度实现二选一（wikiagent.task.mq）。
 * delayLevel=0 表示立即；>0 为 RocketMQ 延迟等级（10s=3、30s=4、2min=6）。
 */
public interface TaskDispatcherPort {

    void dispatch(String taskId, String taskType, int delayLevel);

    /** 看门狗延迟消息：到点核对 owner 租约是否存活（崩溃恢复双保险）。 */
    void dispatchWatchdog(String taskId, String ownerWorkerId, int delayLevel);
}
