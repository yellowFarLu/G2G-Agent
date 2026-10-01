package com.wikiagent.domain.task.ports;

/**
 * 进度端口：worker/心跳发布任务整体进度。
 */
public interface ProgressPort {

    void publish(String taskId, int percent, String currentStep);
}
