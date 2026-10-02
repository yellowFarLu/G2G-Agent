package com.wikiagent.domain.task;

/**
 * 控制标志（Redis 控制键 flag 取值，规格 5.1；Worker 仅在步骤边界响应）。
 * NONE 表示无控制指令（键缺失或已清除）。
 */
public enum ControlFlag {
    NONE,
    PAUSE,
    CANCEL
}
