package com.wikiagent.application.agent.pero;

/**
 * v6 §20 PERO 主循环的最终答案生成器端口。
 * <p>
 * §20.4 主循环末尾：{@code generator.generate(ctx, handover)} 汇总节点产物生成最终答案。
 * 实施示例：用 Spotlighting + 交接清单 + field_index 替换构造 Prompt，调 DashScope 流式生成。
 * <p>
 * 本接口作为端口契约（v3-v5 实施时具体化为 {@code PromptComposer + ChatStreamer}）。
 */
public interface Generator {

    /**
     * 汇总节点产物与交接清单生成最终答案。
     *
     * @param ctx     感知上下文
     * @param handover 交接清单（含已执行节点产物、放弃路径、数据引用索引）
     * @return 最终答案文本
     */
    String generate(Perception ctx, Handover handover);
}
