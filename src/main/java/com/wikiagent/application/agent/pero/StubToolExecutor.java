package com.wikiagent.application.agent.pero;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * v1-v2 路径（{@code wikiagent.pero.enabled=false}）的 ToolExecutor 占位实现。
 * <p>
 * v1-v2 路径工具调用由 {@code NodeExecutor} 直接分派到真实工具组件，
 * 本类仅为满足 ReActExecutor 构造注入而存在（该执行器在 v1-v2 路径不被使用）。
 * v6 PERO 路径（缺省启用）由 {@link PeroToolExecutor} 真实分派五个工具。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.pero.enabled", havingValue = "false")
public class StubToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(StubToolExecutor.class);

    @Override
    public String invoke(ReActAction action, Perception ctx) {
        log.warn("[stub] 工具调用未实现 action={} userId={}", action.name(), ctx.userId());
        return "[STUB] 工具 " + action.name() + " 调用未实现（v3-v5 实施后替换）";
    }
}
