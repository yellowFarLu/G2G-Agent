package com.wikiagent.domain.task;

/**
 * 暂停信号：ReAct 迭代边界 / 步骤边界检查点抛出，worker 置 SUSPENDED。
 */
public class PauseSignalException extends ControlSignalException {

    public PauseSignalException() {
        super("任务收到暂停信号");
    }
}
