package com.wikiagent.application.task;

/**
 * 控制标志乐观版本冲突（expectedVersion 不匹配，规格 5.1）。
 * REST 层映射 409（Task 10）。
 */
public class OptimisticControlConflictException extends RuntimeException {

    public OptimisticControlConflictException(String message) {
        super(message);
    }
}
