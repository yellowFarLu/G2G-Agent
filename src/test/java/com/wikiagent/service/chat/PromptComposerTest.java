package com.wikiagent.service.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptComposerTest {

    @Test
    void 系统提示词应包含反编造与引用规范() {
        String sys = PromptComposer.SYSTEM;
        assertTrue(sys.contains("不得使用参考资料之外的知识"), "应包含禁止编造约束");
        assertTrue(sys.contains("标注来源编号"), "应包含引用规范");
        assertTrue(sys.contains("如实说明"), "应包含资料不足时如实说明的要求");
    }

    @Test
    void 用户提示词应包含完整上下文与问题() {
        String context = "[1] 来源: 报销制度.md\n差旅报销需在 7 个工作日内提交。\n\n";
        String user = PromptComposer.user(context, "报销时限是多久？");
        assertTrue(user.contains("报销制度.md"));
        assertTrue(user.contains("7 个工作日"));
        assertTrue(user.endsWith("报销时限是多久？\n\n请基于以上参考资料回答；若资料不足以回答，请如实说明。")
                || user.contains("用户问题：报销时限是多久？"));
    }

    @Test
    void 文档内容中的花括号应原样保留不被解析() {
        String context = "[1] 来源: api.md\n配置格式：{\"key\": \"{value}\"}\n\n";
        String user = PromptComposer.user(context, "配置格式是什么？");
        // 内容直接拼接而非模板渲染，{value} 不应被当作占位符替换
        assertTrue(user.contains("{\"key\": \"{value}\"}"));
        assertEquals(-1, user.indexOf("Placeholder"), "不应出现占位符渲染痕迹");
    }
}
