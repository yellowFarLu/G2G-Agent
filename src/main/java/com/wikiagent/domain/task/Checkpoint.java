package com.wikiagent.domain.task;

/**
 * 步骤检查点：不可变 JSON 字符串快照，用于断点续跑。
 */
public record Checkpoint(String json) {
}
