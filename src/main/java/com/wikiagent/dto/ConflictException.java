package com.wikiagent.dto;

/**
 * 业务状态冲突（并发接管、非终态重放、过期版本等）：REST 层映射 409。
 * 继承 IllegalStateException 以兼容既有「非法状态」语义判断。
 */
public class ConflictException extends IllegalStateException {

    public ConflictException(String message) {
        super(message);
    }
}
