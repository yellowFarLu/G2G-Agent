package com.wikiagent.domain.extract;

import java.util.List;
import java.util.Optional;

/**
 * 域字段抽取 Schema（注册表条目，版本化；字段证据与血缘落库时透传 key+version）。
 */
public record ExtractionSchema(
        String key,
        String version,
        String description,
        List<FieldDef> fields) {

    public ExtractionSchema {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public Optional<FieldDef> field(String name) {
        return fields.stream().filter(f -> f.name().equals(name)).findFirst();
    }
}
