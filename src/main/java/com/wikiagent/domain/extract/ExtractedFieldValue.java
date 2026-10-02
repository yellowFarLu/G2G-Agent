package com.wikiagent.domain.extract;

import java.util.List;

/**
 * 单个字段的抽取结果：值（统一字符串承载，type 标识语义类型）+ 置信度 + 证据 + 校验结论。
 * 未抽取到的 required 字段表现为 value=null + errors 含 required 提示。
 */
public record ExtractedFieldValue(
        String key,
        String value,
        FieldValueType valueType,
        double confidence,
        FieldSource source,
        boolean valid,
        List<String> errors,
        List<FieldEvidence> evidence) {

    public ExtractedFieldValue {
        errors = errors == null ? List.of() : List.copyOf(errors);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public boolean lowConfidence(double threshold) {
        return value != null && confidence < threshold;
    }
}
