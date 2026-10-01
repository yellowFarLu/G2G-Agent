package com.wikiagent.domain.task;

/**
 * 取消信号：步骤/迭代边界抛出，worker 调 onCancel 后置 CANCELLED。
 */
public class CancelSignalException extends ControlSignalException {

    public CancelSignalException() {
        super("任务收到取消信号");
    }
}
