package com.wikiagent.service.chat;

import com.wikiagent.application.prompt.PromptTemplateService;
import com.wikiagent.domain.prompt.PromptTemplate;
import com.wikiagent.domain.prompt.RenderedPrompt;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void 模板感知系统提示词命中模板返回渲染结果() {
        PromptTemplateService svc = mock(PromptTemplateService.class);
        when(svc.render("chat.system", Map.of()))
                .thenReturn(Optional.of(new RenderedPrompt("模板系统提示", 2)));

        RenderedPrompt rp = PromptComposer.systemPrompt(svc);
        assertEquals("模板系统提示", rp.content());
        assertEquals(2, rp.version());
    }

    @Test
    void 模板感知系统提示词未命中模板回退静态常量() {
        PromptTemplateService svc = mock(PromptTemplateService.class);
        when(svc.render("chat.system", Map.of())).thenReturn(Optional.empty());

        RenderedPrompt rp = PromptComposer.systemPrompt(svc);
        assertEquals(PromptComposer.SYSTEM, rp.content());
        assertEquals(-1, rp.version());
    }

    @Test
    void 模板感知用户提示词命中模板渲染占位符() {
        PromptTemplateService svc = mock(PromptTemplateService.class);
        String tmpl = "参考资料：{context}\n\n问题：{question}\n请回答。";
        when(svc.render("chat.user", Map.of("context", "ctx", "question", "qst")))
                .thenReturn(Optional.of(new RenderedPrompt(tmpl, 3)));

        RenderedPrompt rp = PromptComposer.userPrompt(svc, "ctx", "qst");
        assertTrue(rp.content().contains("ctx"));
        assertTrue(rp.content().contains("qst"));
        assertEquals(3, rp.version());
    }

    @Test
    void 模板感知用户提示词未命中模板回退静态拼接() {
        PromptTemplateService svc = mock(PromptTemplateService.class);
        when(svc.render("chat.user", Map.of("context", "ctx", "question", "qst")))
                .thenReturn(Optional.empty());

        RenderedPrompt rp = PromptComposer.userPrompt(svc, "ctx", "qst");
        String expected = PromptComposer.user("ctx", "qst");
        assertEquals(expected, rp.content());
        assertEquals(-1, rp.version());
    }
}
