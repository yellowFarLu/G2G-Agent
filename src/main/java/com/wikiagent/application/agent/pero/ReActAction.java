package com.wikiagent.application.agent.pero;

/**
 * v6 §20 PERO 节点内 ReAct 的 Action 描述。
 * <p>
 * ReAct 论文（arXiv:2210.03629）中 Action = 工具名 + 参数。
 * {@code name="FINAL"} 表示节点结束，{@code args} 为最终答案。
 * <p>
 * 独立为 record 而非 ReActStep 内部类，便于 {@link ToolExecutor} 接口引用。
 */
public record ReActAction(String name, String args) {

    /** 是否为 FINAL 动作（节点结束）。 */
    public boolean isFinal() {
        return name == null || "FINAL".equals(name);
    }
}
