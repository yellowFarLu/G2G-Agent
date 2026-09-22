package com.wikiagent.domain.agent;

/**
 * v6 §20.5 ReActExecutor 单次 LLM 调用的结构化输出。
 * <p>
 * ReAct 论文（arXiv:2210.03629）的"Thought + Action"在同一 LLM 调用中产生
 * （见 §20.5 {@code callReAct(model, prompt, allowedTools)}）。
 * 本 record 描述单次 LLM 输出，{@link ReActResult} 收集循环内多次调用。
 * <ul>
 *   <li>{@code thought}      — LLM 的推理</li>
 *   <li>{@code action}        — Action 对象，{@code null} 或 {@code name="FINAL"} 表示结束</li>
 *   <li>{@code finalAnswer}  — FINAL 时的最终答案</li>
 * </ul>
 * {@code toTAO()} 把当前 step + 后续 Observation 转为三元组用于 trace。
 */
public record ReActStep(String thought, Action action, String finalAnswer) {

    /** 把 ReActStep 与 Observation 合成三元组。 */
    public ThoughtActionObservation toTAO(String observation) {
        String actionName = action == null ? "FINAL" : action.name();
        String actionArgs = action == null ? null : action.args();
        return new ThoughtActionObservation(thought, actionName, actionArgs, observation);
    }

    /** Action 子结构。{@code name="FINAL"} 表示节点结束。 */
    public record Action(String name, String args) {}
}
