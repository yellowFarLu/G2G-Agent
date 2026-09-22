package com.wikiagent.application.agent.pero;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * v6 §20 ToolExecutor 端口的默认 stub 实现（自洽兜底）。
 * <p>
 * v3-v5 实施时由基于 Spring AI {@code @Tool / ToolCallback / ToolContext} 的
 * 具体实现替换；本类仅返回工具未实现的提示文本，便于编译启动与 §13.8 验收调试。
 * <p>
 * 当 v3-v5 工具实施后，{@link ReActExecutor#execute} 才能真正调用工具。
 */
@Component
public class StubToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(StubToolExecutor.class);

    @Override
    public String invoke(ReActAction action, Perception ctx) {
        log.warn("[stub] 工具调用未实现 action={} userId={}", action.name(), ctx.userId());
        return "[STUB] 工具 " + action.name() + " 调用未实现（v3-v5 实施后替换）";
    }
}
