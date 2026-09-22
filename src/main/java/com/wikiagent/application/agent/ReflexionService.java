package com.wikiagent.application.agent;

import com.wikiagent.domain.agent.PlanStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * v1-v2 §2.5 Reflexion 反思服务。
 * <p>
 * 基于 Reflexion 论文（arXiv:2303.11366）：节点失败后用自然语言生成"反思"，
 * 指导重试策略。与 v6 {@code LlmReflector} 的区别：
 * <ul>
 *   <li>本类只在节点失败路径触发，返回纯文本反思（不输出结构化 JSON）</li>
 *   <li>反思文本作为 hint 注入重试节点的 PlanStep</li>
 * </ul>
 * 仅在 v1-v2 路径激活（{@code wikiagent.pero.enabled=false}）。
 */
@Service
@ConditionalOnProperty(name = "wikiagent.pero.enabled", havingValue = "false")
public class ReflexionService {

    private static final Logger log = LoggerFactory.getLogger(ReflexionService.class);

    static final String REFLECT_SYSTEM = """
            你是企业知识库 Agent 的反思器。基于失败的节点目标、错误信息与上下文，输出一段简短的反思文本，
            用于指导下一次重试。只输出反思文本，不要输出 JSON 或其他格式。
            反思应包含：
            1. 失败原因的简要分析
            2. 下次重试应调整的具体策略（如换关键词、调整参数、换工具）
            限制在 3 句话以内，用简体中文。
            """;

    private final ChatModel chatModel;

    public ReflexionService(@Lazy ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 对失败节点进行反思，返回反思文本以指导重试。
     * <p>
     * LLM 调用失败时返回兜底反思文本，不阻断链路。
     *
     * @param failedStep    失败的节点
     * @param errorMessage  错误信息
     * @param userId        用户 id
     * @param sessionId     会话 id
     * @return 反思文本
     */
    public String reflect(PlanStep failedStep, String errorMessage, String userId, String sessionId) {
        if (failedStep == null) {
            return "节点为空，无法反思";
        }
        try {
            String user = "失败节点 id: " + failedStep.id()
                    + "\n节点目标: " + failedStep.goal()
                    + "\n节点类型: " + failedStep.stepType()
                    + "\n用户 id: " + userId
                    + "\n会话 id: " + sessionId
                    + "\n错误信息: " + (errorMessage == null ? "未知" : errorMessage)
                    + "\n请输出反思文本。";
            String out = call(REFLECT_SYSTEM, user);
            if (out == null || out.isBlank()) {
                return defaultReflection(failedStep, errorMessage);
            }
            return out.strip();
        } catch (Exception e) {
            log.warn("反思调用失败: {}", e.getMessage());
            return defaultReflection(failedStep, errorMessage);
        }
    }

    private String call(String system, String user) {
        var resp = chatModel.call(new Prompt(List.of(
                new SystemMessage(system), new UserMessage(user))));
        if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) {
            return null;
        }
        return resp.getResult().getOutput().getText();
    }

    /** 兜底反思：LLM 不可用时的静态建议。 */
    private static String defaultReflection(PlanStep step, String errorMessage) {
        return "节点 " + step.id() + " 失败（" + (errorMessage == null ? "未知错误" : errorMessage)
                + "）。建议：检查工具参数与查询关键词，必要时换一种表述重试。";
    }
}
