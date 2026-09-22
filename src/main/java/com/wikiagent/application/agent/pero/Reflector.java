package com.wikiagent.application.agent.pero;

import com.wikiagent.domain.agent.PlanStep;
import com.wikiagent.domain.agent.ReActResult;
import com.wikiagent.domain.agent.Reflection;

/**
 * v6 §20.5 节点完成后的反思接口（Reflexion 论文 arXiv:2303.11366）。
 * <p>
 * 反思时机：
 * <ul>
 *   <li>{@link #reflect}              — 节点正常完成后反思，判断是否需要重做（Self-Refine 同步精修）</li>
 *   <li>{@link #reflectOnFailure}    — 节点抛异常后反思，判断是否重试（Reflexion 失败重试）</li>
 * </ul>
 * 反思文本通过 {@link EpisodicMemory#put} 写入 Redis，跨会话复用。
 * <p>
 * 实施示例：用 {@code qwen-3.8-max}（{@code wikiagent.pero.reflect.model}）作为反思模型，
 * 提示词让 LLM 输出结构化 Reflection（needsRework / shouldRetry / reason / planAdjustments）。
 */
public interface Reflector {

    /** 节点正常完成后反思。 */
    Reflection reflect(PlanStep step, ReActResult result, Perception ctx);

    /** 节点失败时反思。 */
    Reflection reflectOnFailure(PlanStep step, Throwable e, Perception ctx);
}
