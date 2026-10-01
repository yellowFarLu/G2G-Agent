package com.wikiagent.domain.task;

/**
 * 控制信号异常（暂停/取消）：必须穿透 handler 的通用 catch，由 worker 在步骤边界捕获分类。
 */
public class ControlSignalException extends RuntimeException {

    public ControlSignalException(String message) {
        super(message);
    }
}
