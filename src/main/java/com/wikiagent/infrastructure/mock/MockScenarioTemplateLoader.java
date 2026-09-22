package com.wikiagent.infrastructure.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * v3 §7.4 Mock 意图模板加载器。
 * <p>
 * 从 classpath:mock-scenarios/*.json 加载 4 个 Mock 意图场景模板。
 */
@Component
public class MockScenarioTemplateLoader {

    private static final Logger log = LoggerFactory.getLogger(MockScenarioTemplateLoader.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, JsonNode> templates = new HashMap<>();

    public MockScenarioTemplateLoader() {
        loadTemplates();
    }

    private void loadTemplates() {
        String[] intents = {"ai_coding", "customer_intake", "business_rule_config", "order_query"};
        for (String intent : intents) {
            String path = "mock-scenarios/" + intent + ".json";
            try (InputStream is = new ClassPathResource(path).getInputStream()) {
                JsonNode node = objectMapper.readTree(is);
                templates.put(intent, node);
                log.info("加载 Mock 场景模板: {} ({} stages)", intent,
                        node.has("stages") ? node.get("stages").size() : 0);
            } catch (IOException e) {
                log.warn("加载 Mock 场景模板失败: {} - {}", path, e.getMessage());
            }
        }
    }

    /**
     * 获取指定意图的场景模板。
     */
    public JsonNode getTemplate(String intent) {
        return templates.get(intent);
    }

    /**
     * 是否已加载该意图的模板。
     */
    public boolean hasTemplate(String intent) {
        return templates.containsKey(intent);
    }

    /**
     * 已加载的模板数量。
     */
    public int templateCount() {
        return templates.size();
    }
}
