package com.wikiagent.service.chat;

/**
 * Prompt 组装。纯静态逻辑，便于单元测试。
 * 参考资料内容直接拼接（不走模板渲染），避免文档中的 { } 被误解析为占位符。
 */
public final class PromptComposer {

    public static final String SYSTEM = """
            你是企业知识库助手，请严格遵守以下规则：
            1. 仅依据下方提供的参考资料回答用户问题，不得使用参考资料之外的知识编造内容。
            2. 引用资料中的关键事实时，在句末标注来源编号，如 [1]、[2]。
            3. 如果参考资料不足以回答问题，如实说明"知识库中未找到足够的信息"，并指出缺少什么，不要编造。
            4. 用简体中文回答，条理清晰、简明扼要。
            """;

    private PromptComposer() {
    }

    public static String user(String context, String question) {
        return "参考资料：\n\n" + context.strip() + "\n\n用户问题：" + question.strip()
                + "\n\n请基于以上参考资料回答；若资料不足以回答，请如实说明。";
    }
}
