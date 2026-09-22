package com.wikiagent.domain.agent;

import java.util.List;

/**
 * v6 §20.5 ReActExecutor.execute() 的返回结果。
 * <p>
 * 节点内 ReAct 循环有两种结束方式：
 * <ol>
 *   <li>{@link #done(String, List)} — LLM 输出 FINAL，节点正常结束，含最终答案</li>
 *   <li>{@link #truncated(List)}    — 达到 maxIter 仍未结束，trace 保留供 Reflector 评估</li>
 * </ol>
 * 字段：
 * <ul>
 *   <li>{@code done}          — 是否正常结束</li>
 *   <li>{@code finalAnswer}   — done=true 时的最终答案，truncated 时为 null</li>
 *   <li>{@code trace}         — 全部 ReActStep 三元组轨迹</li>
 * </ul>
 */
public record ReActResult(boolean done, String finalAnswer,
                          List<ThoughtActionObservation> trace) {

    public static ReActResult done(String finalAnswer, List<ThoughtActionObservation> trace) {
        return new ReActResult(true, finalAnswer, trace);
    }

    public static ReActResult truncated(List<ThoughtActionObservation> trace) {
        return new ReActResult(false, null, trace);
    }

    public boolean isTruncated() {
        return !done;
    }
}
