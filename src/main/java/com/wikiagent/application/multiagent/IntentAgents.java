package com.wikiagent.application.multiagent;

import com.wikiagent.application.agent.pero.PeroAgent;
import com.wikiagent.application.agent.pero.Perception;
import com.wikiagent.application.task.handler.AgentTaskLauncher;
import com.wikiagent.service.chat.SseSender;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * v6 §21.5 5 个 {@link IntentAgent} 实现（1 真实 + 4 Mock）。
 * <p>
 * <b>真实 IntentAgent</b>：
 * <ul>
 *   <li>{@link KnowledgeQaAgent} — 委托 §20 {@link PeroAgent} PERO 主循环执行</li>
 * </ul>
 * <b>Mock IntentAgent</b>（§7.4 模板未实施，返回预设响应字符串，对外表现为真实路径）：
 * <ul>
 *   <li>{@link AiCodingAgent} — AI Coding</li>
 *   <li>{@link CustomerIntakeAgent} — 客户接入</li>
 *   <li>{@link BusinessRuleConfigAgent} — 业务规则配置</li>
 *   <li>{@link OrderQueryAgent} — 订单查询</li>
 * </ul>
 * <p>
 * 4 个 Mock 复用 {@link MockIntentAgent} 抽象基类：固定响应文本 + 用户输入回显。
 * v3-v5 实施时由 {@code MockScenarioTemplateLoader}（§7.4 模板）替换为真实 Mock 数据加载。
 * <p>
 * 注：本文件包含 5 个 package-private @Component 顶层类 + 1 个 package-private abstract 基类，
 * Spring 通过 ASM 字节码扫描自动注册（与 @ComponentScan 兼容）。
 */
final class IntentAgents {

    private IntentAgents() {
    }

    /**
     * 真实 KnowledgeQa：委托 §20 {@link PeroAgent} PERO 主循环执行（Plan→Execute→Reflect→Optimize）。
     * <p>
     * PeroAgent 内部会调用 PeroPlanner.plan() → ReActExecutor.execute() → LlmReflector.reflect()
     * → SimpleOptimizer.optimize() → SimpleGenerator.generate()，完整走 §20 主循环。
     */
    @Component
    static class KnowledgeQaAgent implements IntentAgent {

        private final PeroAgent peroAgent;
        private final ObjectProvider<AgentTaskLauncher> taskLauncherProvider;

        KnowledgeQaAgent(PeroAgent peroAgent,
                         ObjectProvider<AgentTaskLauncher> taskLauncherProvider) {
            this.peroAgent = peroAgent;
            this.taskLauncherProvider = taskLauncherProvider;
        }

        @Override
        public String intent() {
            return "knowledge_qa";
        }

        @Override
        public void invoke(Perception ctx, SseSender sse) {
            // 任务框架开启时：提交 AGENT 任务并把任务流桥接回当前 SSE 客户端（支持暂停/取消/恢复）；
            // 未开启（AgentTaskLauncher Bean 不存在）走原 PERO 同步链路，行为不变。
            AgentTaskLauncher launcher = taskLauncherProvider.getIfAvailable();
            if (launcher != null) {
                launcher.launchAndBridge(ctx.userId(), ctx.sessionId(), ctx.userInput(), sse);
                return;
            }
            peroAgent.run(ctx.userId(), ctx.sessionId(), ctx.userInput(), sse);
        }
    }

    /** Mock AI Coding：返回预设响应（§7.4 模板未实施）。 */
    @Component
    static class AiCodingAgent extends MockIntentAgent {
        AiCodingAgent() {
            super("ai_coding", "已生成代码骨架并写入临时文件（Mock，§7.4 模板未启用）");
        }
    }

    /** Mock 客户接入：返回预设响应。 */
    @Component
    static class CustomerIntakeAgent extends MockIntentAgent {
        CustomerIntakeAgent() {
            super("customer_intake", "客户接入流程已记录（Mock，§7.4 模板未启用）");
        }
    }

    /** Mock 业务规则配置：返回预设响应。 */
    @Component
    static class BusinessRuleConfigAgent extends MockIntentAgent {
        BusinessRuleConfigAgent() {
            super("business_rule_config", "业务规则已更新（Mock，§7.4 模板未启用）");
        }
    }

    /** Mock 订单查询：返回预设响应。 */
    @Component
    static class OrderQueryAgent extends MockIntentAgent {
        OrderQueryAgent() {
            super("order_query", "订单查询结果：暂无数据（Mock，§7.4 模板未启用）");
        }
    }

    /**
     * Mock 抽象基类：返回固定响应文本 + 用户输入回显，对外表现为真实 Agent 路径。
     * <p>
     * v3-v5 实施时由 {@code MockScenarioTemplateLoader}（§7.4 模板）替换为真实 Mock 数据加载，
     * 当前仅作 v6 自洽默认实现。
     */
    abstract static class MockIntentAgent implements IntentAgent {

        private final String intentName;
        private final String mockResponse;

        protected MockIntentAgent(String intentName, String mockResponse) {
            this.intentName = intentName;
            this.mockResponse = mockResponse;
        }

        @Override
        public String intent() {
            return intentName;
        }

        @Override
        public void invoke(Perception ctx, SseSender sse) {
            String response = "[Mock][" + intentName + "] " + mockResponse
                    + "\n用户输入：" + ctx.userInput();
            sse.send("delta", Map.of("text", response));
            sse.send("done", Map.of());
        }
    }
}
