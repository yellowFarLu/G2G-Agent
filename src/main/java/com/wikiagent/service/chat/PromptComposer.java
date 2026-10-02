package com.wikiagent.service.chat;

import com.wikiagent.application.prompt.PromptTemplateService;
import com.wikiagent.domain.prompt.RenderedPrompt;

import java.util.Map;
import java.util.Optional;

/**
 * Prompt 组装。
 * <p>
 * 无参静态方法保留纯字符串拼接（模板不存在时的兜底逻辑）。
 * 模板感知方法接 {@link PromptTemplateService}：按 code 查 ACTIVE 模板渲染，
 * 返回 {@link RenderedPrompt}（含实际使用版本）；模板不存在时回退到静态拼接
 * （version=-1），保证无模板时行为与旧逻辑一致。
 */
public final class PromptComposer {

    public static final String SYSTEM = """
            你是企业知识库助手，请严格遵守以下规则：
            1. 仅依据下方提供的参考资料回答用户问题，不得使用参考资料之外的知识编造内容。
            2. 引用资料中的关键事实时，在句末标注来源编号，如 [1]、[2]。
            3. 如果参考资料不足以回答问题，如实说明"知识库中未找到足够的信息"，并指出缺少什么，不要编造。
            4. 用简体中文回答，条理清晰、简明扼要。
            """;

    /** 知识库无相关证据时的固定回答。 */
    public static final String NO_CONTEXT = """
            知识库中未检索到与该问题相关的内容，暂时无法回答。请先上传相关文档，或换一种问法。""";

    /** 模板 code：CHAT 系统提示词。 */
    public static final String CODE_CHAT_SYSTEM = "chat.system";

    /** 模板 code：CHAT 用户提示词（含 {context}/{question} 占位符）。 */
    public static final String CODE_CHAT_USER = "chat.user";

    private PromptComposer() {
    }

    public static String user(String context, String question) {
        return "参考资料：\n\n" + context.strip() + "\n\n用户问题：" + question.strip()
                + "\n\n请基于以上参考资料回答；若资料不足以回答，请如实说明。";
    }

    /**
     * 模板感知的系统提示词：模板服务可用且存在 ACTIVE 模板时用模板，
     * 否则回退到 {@link #SYSTEM} 静态常量。
     */
    public static RenderedPrompt systemPrompt(PromptTemplateService templateService) {
        if (templateService != null) {
            Optional<RenderedPrompt> opt = templateService.render(CODE_CHAT_SYSTEM, Map.of());
            if (opt.isPresent()) {
                return opt.get();
            }
        }
        return RenderedPrompt.fallback(SYSTEM);
    }

    /**
     * 模板感知的用户提示词：模板服务可用且存在 ACTIVE 模板时用模板渲染
     * （占位符 {context}/{question}），否则回退到 {@link #user(String, String)}。
     */
    public static RenderedPrompt userPrompt(PromptTemplateService templateService,
                                            String context, String question) {
        if (templateService != null) {
            Optional<RenderedPrompt> opt = templateService.render(CODE_CHAT_USER,
                    Map.of("context", context.strip(), "question", question.strip()));
            if (opt.isPresent()) {
                return opt.get();
            }
        }
        return RenderedPrompt.fallback(user(context, question));
    }
}
