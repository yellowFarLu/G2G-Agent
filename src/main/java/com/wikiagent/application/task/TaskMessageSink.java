package com.wikiagent.application.task;

/**
 * 投递消息汇：投递层（RocketMQ Consumer / LocalTaskDispatcher）调用，
 * Task 8 的 TaskWorker 实现。看门狗消息按属性分流到 onWatchdog。
 */
public interface TaskMessageSink {

    /** 普通投递：taskId + taskType（RocketMQ Tag）。 */
    void onMessage(String taskId, String taskType);

    /** 看门狗触发：核对 owner 租约是否存活（Task 9 实现真实逻辑）。 */
    void onWatchdog(String taskId, String ownerWorkerId);
}
