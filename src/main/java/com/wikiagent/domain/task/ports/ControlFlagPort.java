package com.wikiagent.domain.task.ports;

import com.wikiagent.domain.task.ControlFlag;

/**
 * 控制标志端口（暂停/取消协调态）：乐观版本控制，expectedVersion 不匹配抛
 * OptimisticControlConflictException（application/task 包，Task 6 落地）。
 * 权威复核仍以 MySQL control_version 为准（worker 每边界双读）。
 */
public interface ControlFlagPort {

    void requestPause(String taskId, int expectedVersion);

    void requestCancel(String taskId, int expectedVersion);

    ControlFlag read(String taskId);

    int currentVersion(String taskId);

    void clear(String taskId);
}
