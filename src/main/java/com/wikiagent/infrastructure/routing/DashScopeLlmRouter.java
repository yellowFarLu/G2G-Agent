package com.wikiagent.infrastructure.routing;

import com.wikiagent.application.llm.ModelCallRecorder;
import com.wikiagent.domain.llm.ModelCallLogPurpose;
import com.wikiagent.domain.routing.Intent;
import com.wikiagent.domain.routing.LlmRouterPort;
import com.wikiagent.domain.routing.RouteDecision;
import com.wikiagent.service.agent.JsonExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * v1-v2 §7 LLM 路由层 - DashScope 实现适配器。
 * <p>
 * 流程：
 * <ol>
 *   <li>用 intentChatModel 把用户输入分类为 5 类 Intent 之一（输出 JSON 含 {@code intent}）</li>
 *   <li>简单意图（KNOWLEDGE_QA / ORDER_QUERY）路由到 simple-model</li>
 *   <li>复杂意图（AI_CODING / CUSTOMER_INTAKE / BUSINESS_RULE_CONFIG）路由到 complex-model</li>
 *   <li>LLM 调用失败或 JSON 解析失败 → RouteDecision.fallback()（knowledge_qa）</li>
 * </ol>
 * 实施校正：原 @ConditionalOnBean(name="intentChatModel") 受 @Configuration 与组件
 * 扫描处理顺序影响不稳定；intentChatModel 由 DashScopeMultiModelFactory 恒创建
 * （API Key 缺失时为 NoOpChatModel），故改为无条件 @Service，按 @Qualifier 注入。
 */
@Service
public class DashScopeLlmRouter implements LlmRouterPort {

    private static final Logger log = LoggerFactory.getLogger(DashScopeLlmRouter.class);

    static final String INTENT_SYSTEM = """
            你是企业知识库系统的意图分类器。分析用户输入，只输出一个 JSON，不要输出任何其他文字：
            {"intent":"<intent_code>","reason":"<简短理由>"}
            intent_code 必须是以下之一：
            - knowledge_qa：知识问答（基于知识库的回答）
            - ai_coding：AI 编程辅助
            - customer_intake：客户接入/录入
            - business_rule_config：业务规则配置
            - order_query：订单查询
            判断原则：用户输入与哪类业务最相关就归为哪类；无法明确归类时默认 knowledge_qa。
            """;

    private final ChatModel intentChatModel;
    private final String simpleModel;
    private final String complexModel;
    /** E4：可选打点器。 */
    private final ModelCallRecorder recorder;
    private final String intentModelName;

    public DashScopeLlmRouter(@Qualifier("intentChatModel") ChatModel intentChatModel,
                              @Value("${wikiagent.routing.simple-model:qwen-plus}") String simpleModel,
                              @Value("${wikiagent.routing.complex-model:qwen-max}") String complexModel,
                              @Value("${wikiagent.routing.intent-model:qwen-flash}") String intentModel,
                              ObjectProvider<ModelCallRecorder> recorder) {
        this.intentChatModel = intentChatModel;
        this.simpleModel = simpleModel;
        this.complexModel = complexModel;
        this.intentModelName = intentModel;
        this.recorder = recorder == null ? null : recorder.getIfAvailable();
    }

    @Override
    public RouteDecision route(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return RouteDecision.fallback();
        }
        try {
            String out = callIntent(userInput);
            Map<String, Object> json = JsonExtractor.parseObject(out);
            if (json.isEmpty()) {
                log.warn("意图分类输出无法解析，降级 fallback: {}", out);
                return RouteDecision.fallback();
            }
            Object intentObj = json.get("intent");
            if (intentObj == null) {
                log.warn("意图分类输出缺 intent 字段，降级 fallback: {}", out);
                return RouteDecision.fallback();
            }
            Intent intent = Intent.fromCode(String.valueOf(intentObj));
            String reason = json.get("reason") == null ? "" : String.valueOf(json.get("reason"));
            return routeByIntent(intent, reason);
        } catch (Exception e) {
            log.warn("意图分类调用失败，降级 fallback: {}", e.getMessage());
            return RouteDecision.fallback();
        }
    }

    /** 按意图复杂度路由到 simple/complex 模型。 */
    private RouteDecision routeByIntent(Intent intent, String reason) {
        if (intent.isComplex()) {
            RouteDecision d = RouteDecision.complex(intent, complexModel);
            return new RouteDecision(d.intent(), d.modelName(), d.isComplex(),
                    reason.isBlank() ? d.reason() : reason);
        }
        RouteDecision d = RouteDecision.simple(intent, simpleModel);
        return new RouteDecision(d.intent(), d.modelName(), d.isComplex(),
                reason.isBlank() ? d.reason() : reason);
    }

    private String callIntent(String userInput) {
        long started = System.currentTimeMillis();
        try {
            var resp = intentChatModel.call(new Prompt(List.of(
                    new SystemMessage(INTENT_SYSTEM),
                    new UserMessage(userInput))));
            long latency = System.currentTimeMillis() - started;
            if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) {
                recordIntent(null, null, latency, false);
                return null;
            }
            recordIntent(tokensOf(resp.getMetadata() == null ? null : resp.getMetadata().getUsage(), true),
                    tokensOf(resp.getMetadata() == null ? null : resp.getMetadata().getUsage(), false),
                    latency, true);
            return resp.getResult().getOutput().getText();
        } catch (Exception e) {
            recordIntent(null, null, System.currentTimeMillis() - started, false);
            throw e;
        }
    }

    private void recordIntent(Integer in, Integer out, long latency, boolean ok) {
        if (recorder != null) {
            recorder.record(ModelCallLogPurpose.INTENT, "dashscope", intentModelName,
                    in, out, latency, ok, null, null, null);
        }
    }

    private static Integer tokensOf(Usage usage, boolean prompt) {
        if (usage == null) {
            return null;
        }
        return prompt ? usage.getPromptTokens() : usage.getCompletionTokens();
    }
}
