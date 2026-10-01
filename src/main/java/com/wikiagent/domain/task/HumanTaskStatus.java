package com.wikiagent.domain.task;

/**
 * 人工接管点状态（human_task.status 取值集，规格 2.4）。
 */
public enum HumanTaskStatus {
    OPEN,
    CLAIMED,
    RESOLVED,
    EXPIRED
}
