package com.wikiagent.domain.agent;

/**
 * v6 §20.5 ReActExecutor 单步 ReAct 循环产生的三元组（Thought / Action / Observation）。
 * <p>
 * ReAct 论文（arXiv:2210.03629）定义的循环结构：Thought（推理）→ Action（动作）→
 * Observation（观测）→ 再 Thought。本 record 是这三步的快照，用于 trace 与 episodic memory。
 * <ul>
 *   <li>{@code thought}     — LLM 的本轮推理（自然语言）</li>
 *   <li>{@code actionName}  — 调用的工具名，{@code "FINAL"} 表示节点结束</li>
 *   <li>{@code actionArgs}  — 工具参数 JSON 字符串</li>
 *   <li>{@code observation} — 工具返回结果（FINAL 时为最终答案）</li>
 * </ul>
 */
public record ThoughtActionObservation(String thought, String actionName,
                                       String actionArgs, String observation) {

    /** 节点结束（FINAL）时使用：thought + actionName=FINAL + finalAnswer。 */
    public static ThoughtActionObservation finalAnswer(String thought, String finalAnswer) {
        return new ThoughtActionObservation(thought, "FINAL", null, finalAnswer);
    }
}
