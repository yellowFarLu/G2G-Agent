package com.wikiagent.application.agent.pero;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * v6 §20 Generator 端口的默认实现（自洽兜底）。
 * <p>
 * 汇总已执行节点产物 + 交接清单生成最终答案。
 * v3-v5 实施时由 {@code PromptComposer + ChatStreamer} 替换（含 Spotlighting +
 * 交接清单注入 + field_index 替换），本类为 v6 自洽的最简实现：拼接节点 finalAnswer。
 */
@Component
public class SimpleGenerator implements Generator {

    private static final Logger log = LoggerFactory.getLogger(SimpleGenerator.class);

    @Override
    public String generate(Perception ctx, Handover handover) {
        if (!(handover instanceof MemoryHandover mh)) {
            log.warn("Generator 兜底：handover 非 MemoryHandover，返回兜底文本");
            return "[v6 PERO 兜底] handover 类型不支持，已执行节点产物不可读";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("用户原始请求：").append(ctx.userInput()).append("\n\n");
        if (mh.executedNodes().isEmpty()) {
            sb.append("（未执行任何节点）");
        } else {
            sb.append("已执行节点：\n");
            for (var step : mh.executedNodes()) {
                sb.append("- ").append(step.id()).append(": ").append(step.goal()).append("\n");
            }
        }
        if (!mh.abandonedPaths().isEmpty()) {
            sb.append("\n放弃路径：\n");
            for (String p : mh.abandonedPaths()) {
                sb.append("- ").append(p).append("\n");
            }
        }
        sb.append("\n[v6 PERO 兜底生成] v3-v5 实施后由 PromptComposer + ChatStreamer 替换");
        return sb.toString();
    }
}
