package com.wikiagent.domain.rule.dsl;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次确定性执行的结果：中间量（含输入与每步产出，按序）+ 最终输出子集。
 * 值限定为 JSON 可序列化类型（String/Number/Boolean/List/Map/null）。
 */
public record RuleOutcome(Map<String, Object> intermediates, Map<String, Object> outputs) {

    public RuleOutcome {
        intermediates = intermediates == null ? Map.of() : new LinkedHashMap<>(intermediates);
        outputs = outputs == null ? Map.of() : new LinkedHashMap<>(outputs);
    }
}
