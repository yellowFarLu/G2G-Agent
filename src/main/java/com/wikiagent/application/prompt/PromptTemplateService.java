package com.wikiagent.application.prompt;

import com.wikiagent.domain.prompt.PromptTemplate;
import com.wikiagent.domain.prompt.PromptTemplateRepository;
import com.wikiagent.domain.prompt.RenderedPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * 提示词模板应用服务：按 code 查 ACTIVE 模板 → 占位符替换渲染。
 * <p>
 * 占位符语法：{@code {key}}，key 匹配变量 map 中的键。无法替换的占位符保持原样。
 */
@Service
public class PromptTemplateService {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateService.class);

    private final PromptTemplateRepository repository;

    public PromptTemplateService(PromptTemplateRepository repository) {
        this.repository = repository;
    }

    /**
     * 按 code 查 ACTIVE 模板并渲染。
     *
     * @param code      模板标识
     * @param variables 占位符变量；null 等价于空 map
     * @return 渲染结果；无 ACTIVE 模板返回 empty
     */
    public Optional<RenderedPrompt> render(String code, Map<String, String> variables) {
        Optional<PromptTemplate> opt = repository.findActiveByCode(code);
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        PromptTemplate t = opt.get();
        String rendered = replacePlaceholders(t.content(), variables == null ? Map.of() : variables);
        return Optional.of(new RenderedPrompt(rendered, t.version()));
    }

    /** 占位符替换：{@code {key}} → value；未命中保留原样。 */
    private static String replacePlaceholders(String content, Map<String, String> variables) {
        if (variables.isEmpty()) {
            return content;
        }
        StringBuilder sb = new StringBuilder(content);
        for (Map.Entry<String, String> e : variables.entrySet()) {
            String placeholder = "{" + e.getKey() + "}";
            int idx = 0;
            while ((idx = sb.indexOf(placeholder, idx)) != -1) {
                sb.replace(idx, idx + placeholder.length(), e.getValue());
                idx += e.getValue().length();
            }
        }
        return sb.toString();
    }
}
