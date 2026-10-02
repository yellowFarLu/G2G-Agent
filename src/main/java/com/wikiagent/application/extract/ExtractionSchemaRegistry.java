package com.wikiagent.application.extract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.domain.extract.ExtractionSchema;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ExtractionSchema 注册表：启动时加载 classpath:/extract-schemas/*.json
 * （JSON Schema 子集：required/type/regex/enum）。按 schemaKey 取用；
 * INGEST 参数未指定 schemaKey 或注册表无条目时跳过抽取（默认不抽）。
 */
@Component
public class ExtractionSchemaRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExtractionSchemaRegistry.class);
    private static final String SCHEMA_PATTERN = "classpath*:extract-schemas/*.json";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, ExtractionSchema> schemas = new LinkedHashMap<>();

    @PostConstruct
    void load() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(SCHEMA_PATTERN);
            for (Resource resource : resources) {
                try (var in = resource.getInputStream()) {
                    ExtractionSchema schema = mapper.readValue(in.readAllBytes(), ExtractionSchema.class);
                    if (schema.key() == null || schema.key().isBlank()) {
                        log.warn("抽取 schema 缺少 key，跳过: {}", resource.getFilename());
                        continue;
                    }
                    schemas.put(schema.key(), schema);
                    log.info("已注册抽取 schema: {} v{}（{} 字段）",
                            schema.key(), schema.version(), schema.fields().size());
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("加载抽取 schema 失败: " + e.getMessage(), e);
        }
    }

    public Optional<ExtractionSchema> get(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(schemas.get(key));
    }

    public List<String> keys() {
        return List.copyOf(schemas.keySet());
    }
}
