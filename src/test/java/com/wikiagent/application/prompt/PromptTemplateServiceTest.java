package com.wikiagent.application.prompt;

import com.wikiagent.domain.prompt.PromptTemplate;
import com.wikiagent.domain.prompt.PromptTemplateRepository;
import com.wikiagent.domain.prompt.RenderedPrompt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * PromptTemplateService 单元测试：模板命中、未命中、占位符替换。
 */
class PromptTemplateServiceTest {

    private PromptTemplateRepository repo;
    private PromptTemplateService service;

    @BeforeEach
    void setUp() {
        repo = mock(PromptTemplateRepository.class);
        service = new PromptTemplateService(repo);
    }

    @Test
    void 按code查ACTIVE模板渲染占位符() {
        PromptTemplate tmpl = new PromptTemplate(1L, "chat.system", 2, "你好 {name}",
                PromptTemplate.Status.ACTIVE, null, null);
        when(repo.findActiveByCode("chat.system")).thenReturn(Optional.of(tmpl));

        Optional<RenderedPrompt> opt = service.render("chat.system", Map.of("name", "Wiki"));
        assertTrue(opt.isPresent());
        assertEquals("你好 Wiki", opt.get().content());
        assertEquals(2, opt.get().version());
    }

    @Test
    void 多个占位符全部替换() {
        PromptTemplate tmpl = new PromptTemplate(1L, "chat.user", 1, "{a}+{b}={c}",
                PromptTemplate.Status.ACTIVE, null, null);
        when(repo.findActiveByCode("chat.user")).thenReturn(Optional.of(tmpl));

        Optional<RenderedPrompt> opt = service.render("chat.user",
                Map.of("a", "1", "b", "2", "c", "3"));
        assertTrue(opt.isPresent());
        assertEquals("1+2=3", opt.get().content());
    }

    @Test
    void 无ACTIVE模板返回empty() {
        when(repo.findActiveByCode("missing")).thenReturn(Optional.empty());
        assertTrue(service.render("missing", Map.of()).isEmpty());
    }

    @Test
    void 变量为null等价于空map() {
        PromptTemplate tmpl = new PromptTemplate(1L, "plain", 1, "hello",
                PromptTemplate.Status.ACTIVE, null, null);
        when(repo.findActiveByCode("plain")).thenReturn(Optional.of(tmpl));

        Optional<RenderedPrompt> opt = service.render("plain", null);
        assertTrue(opt.isPresent());
        assertEquals("hello", opt.get().content());
    }

    @Test
    void 未命中占位符保留原样() {
        PromptTemplate tmpl = new PromptTemplate(1L, "partial", 1, "{known} {unknown}",
                PromptTemplate.Status.ACTIVE, null, null);
        when(repo.findActiveByCode("partial")).thenReturn(Optional.of(tmpl));

        Optional<RenderedPrompt> opt = service.render("partial", Map.of("known", "A"));
        assertTrue(opt.isPresent());
        assertEquals("A {unknown}", opt.get().content());
    }
}
