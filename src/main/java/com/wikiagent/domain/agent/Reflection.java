package com.wikiagent.domain.agent;

import java.util.List;

/**
 * v6 §20.5 Reflector 接口返回的反思结果。
 * <p>
 * Reflexion 论文（arXiv:2303.11366）的核心：失败后用自然语言生成"反思"写入
 * episodic memory，下一轮 trial 复用；不改权重，纯语言强化。
 * 本 record 描述一次反思的所有结构化输出：
 * <ul>
 *   <li>{@code stepId}        — 反思对应的节点 id</li>
 *   <li>{@code text}          — 反思文本（写入 episodic memory 跨会话复用）</li>
 *   <li>{@code needsRework}   — 是否需要同节点重做（Self-Refine arXiv:2303.17651 同步精修）</li>
 *   <li>{@code reworkHint}    — needsRework=true 时携带的 hint，传给 ctx.with() 与下一轮 ReAct</li>
 *   <li>{@code shouldRetry}   — 失败路径下是否重试（reflectOnFailure 使用）</li>
 *   <li>{@code reason}        — 不重试/失败原因，写入 handover.abandonPath</li>
 *   <li>{@code planAdjustments}— 对剩余 Plan 的增删改建议（Optimizer 消费）</li>
 * </ul>
 * record 不可变；needsRework / shouldRetry 为 false 时对应字段应为 null/空。
 */
public record Reflection(String stepId, String text, boolean needsRework,
                         String reworkHint, boolean shouldRetry, String reason,
                         List<PlanStep> planAdjustments) {

    /** 节点完成路径的反思。 */
    public static Reflection of(String stepId, String text, boolean needsRework,
                                String reworkHint, List<PlanStep> planAdjustments) {
        return new Reflection(stepId, text, needsRework, reworkHint, false, null, planAdjustments);
    }

    /** 节点失败路径的反思。 */
    public static Reflection onFailure(String stepId, String text, boolean shouldRetry, String reason) {
        return new Reflection(stepId, text, false, null, shouldRetry, reason, null);
    }
}
