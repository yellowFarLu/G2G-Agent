package com.wikiagent.domain.task.ports;

import com.wikiagent.domain.task.TaskEvent;

import java.util.List;

/**
 * 任务事件追加写端口（含纯审计事件，不经过状态机）。
 */
public interface TaskEventRepositoryPort {

    void append(TaskEvent e);

    List<TaskEvent> findByTaskId(String taskId);
}
