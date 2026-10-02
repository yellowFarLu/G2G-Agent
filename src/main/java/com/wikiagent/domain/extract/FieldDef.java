package com.wikiagent.domain.extract;

import java.util.List;

/**
 * 抽取字段定义（JSON Schema 子集：required/type/regex/enum）。
 */
public record FieldDef(
        String name,
        String label,
        FieldValueType type,
        boolean required,
        String pattern,
        List<String> enumValues,
        String description) {

    public FieldDef {
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
    }
}
