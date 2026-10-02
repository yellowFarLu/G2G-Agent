package com.wikiagent.application.rule;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.domain.rule.dsl.RuleEngineException;
import com.wikiagent.domain.rule.dsl.RuleProgram;
import com.wikiagent.domain.rule.dsl.RuleStep;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 规则 DSL 解析器：JSON → RuleProgram；含基础校验。
 */
public class RuleDslParser {

    private final ObjectMapper mapper;

    public RuleDslParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public RuleProgram parse(String dslJson) {
        Map<String, Object> root;
        try {
            root = mapper.readValue(dslJson, new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuleEngineException("DSL JSON 解析失败: " + e.getMessage(), e);
        }
        Object stepsObj = root.get("steps");
        if (!(stepsObj instanceof List<?> rawSteps) || rawSteps.isEmpty()) {
            throw new RuleEngineException("DSL 必须包含非空的 steps 数组");
        }
        List<RuleStep> steps = rawSteps.stream().map(this::toStep).toList();
        Set<String> produced = steps.stream().map(RuleStep::to).collect(Collectors.toSet());

        List<String> outputs = List.of();
        Object outputsObj = root.get("outputs");
        if (outputsObj instanceof List<?> rawOut) {
            outputs = rawOut.stream().map(String::valueOf).toList();
            for (String key : outputs) {
                if (!produced.contains(key)) {
                    throw new RuleEngineException("输出键未由任何步骤产出: " + key);
                }
            }
        }
        return new RuleProgram(steps, outputs);
    }

    @SuppressWarnings("unchecked")
    private RuleStep toStep(Object obj) {
        if (!(obj instanceof Map<?, ?> m)) {
            throw new RuleEngineException("steps 中每项必须是对象");
        }
        String op = String.valueOf(m.get("op"));
        if (!RuleStep.SUPPORTED_OPS.contains(op)) {
            throw new RuleEngineException("不支持的算子: " + op);
        }
        String to = m.get("to") == null ? null : String.valueOf(m.get("to"));
        if (to == null || to.isBlank()) {
            throw new RuleEngineException("规则步骤缺少产出键 to");
        }
        Map<String, Object> params = m.get("params") instanceof Map
                ? (Map<String, Object>) m.get("params")
                : Map.of();
        return new RuleStep(op, params, to);
    }
}
