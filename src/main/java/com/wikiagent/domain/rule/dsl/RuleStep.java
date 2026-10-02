package com.wikiagent.domain.rule.dsl;

import java.util.List;
import java.util.Map;

/**
 * 声明式规则步骤（规格 §D2）：op 为算子名，params 为该算子的参数表。
 * 支持算子：map / const / arith / regex / enum / compare / materialDiff。
 * 每步必须声明 to（产出键），产出进入中间量上下文，后续步骤可引用。
 */
public record RuleStep(String op, Map<String, Object> params, String to) {

    public static final String OP_MAP = "map";
    public static final String OP_CONST = "const";
    public static final String OP_ARITH = "arith";
    public static final String OP_REGEX = "regex";
    public static final String OP_ENUM = "enum";
    public static final String OP_COMPARE = "compare";
    public static final String OP_MATERIAL_DIFF = "materialDiff";

    public static final List<String> SUPPORTED_OPS = List.of(
            OP_MAP, OP_CONST, OP_ARITH, OP_REGEX, OP_ENUM, OP_COMPARE, OP_MATERIAL_DIFF);

    public String paramString(String key) {
        Object v = params.get(key);
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    public List<String> paramStringList(String key) {
        Object v = params.get(key);
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
