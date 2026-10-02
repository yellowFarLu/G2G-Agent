package com.wikiagent.application.eval.suite;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.wikiagent.application.eval.EvalGoldenLoader;
import com.wikiagent.domain.eval.EvalCategory;
import com.wikiagent.domain.eval.SampleResult;
import com.wikiagent.domain.eval.golden.RuleGoldenCase;
import com.wikiagent.domain.rule.dsl.DeterministicRuleEngine;
import com.wikiagent.domain.rule.dsl.RuleOutcome;
import com.wikiagent.domain.rule.dsl.RuleProgram;
import com.wikiagent.domain.rule.dsl.RuleStep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RULE 评测套件（AC-H1/H2）：七算子 DSL 夹具（map/const/arith/regex/enum/compare/materialDiff）
 * 经<b>真实</b> {@link DeterministicRuleEngine} 执行（零 LLM），
 * 与 golden expectedOutput 做规范化深度比对（数值按数值口径，300(int) 与 300(long) 视为一致；
 * materialDiff 行按 List&lt;Map&gt; 递归比对）。
 */
public class RuleEvalSuite {

    private final EvalGoldenLoader loader;
    private final DeterministicRuleEngine engine = new DeterministicRuleEngine();

    public RuleEvalSuite(EvalGoldenLoader loader) {
        this.loader = loader;
    }

    public SuiteOutput run() {
        List<SampleResult> results = new ArrayList<>();
        for (RuleGoldenCase c : loader.loadRuleGolden()) {
            results.add(runOne(c));
        }
        return SuiteOutput.of(results);
    }

    private SampleResult runOne(RuleGoldenCase c) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        try {
            RuleProgram program = parseProgram(loader.readRuleDsl(c.dslFixture()));
            RuleOutcome outcome = engine.run(program, c.input());
            metrics.put("outputs", outcome.outputs());

            int total = c.expectedOutput().size();
            int matched = 0;
            for (Map.Entry<String, Object> e : c.expectedOutput().entrySet()) {
                Object actual = outcome.outputs().get(e.getKey());
                if (!outcome.outputs().containsKey(e.getKey())) {
                    failures.add("缺少输出键: " + e.getKey());
                } else if (deepEquals(e.getValue(), actual)) {
                    matched++;
                } else {
                    failures.add("输出键 " + e.getKey() + " 期望=" + e.getValue()
                            + " 实际=" + actual);
                }
            }
            metrics.put("checksPassed", matched);
            metrics.put("checksTotal", total);
            double score = total == 0 ? 0.0 : (double) matched / total;
            if (failures.isEmpty()) {
                return SampleResult.passed(c.id(), EvalCategory.RULE, score, metrics);
            }
            return SampleResult.failed(c.id(), EvalCategory.RULE, score, metrics,
                    String.join("；", failures));
        } catch (Exception e) {
            return SampleResult.error(c.id(), EvalCategory.RULE,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private RuleProgram parseProgram(String dslJson) {
        JsonNode root = loader.readTree(dslJson);
        List<RuleStep> steps = new ArrayList<>();
        for (JsonNode s : root.path("steps")) {
            Map<String, Object> params = loader.mapper().convertValue(
                    s.path("params"), new TypeReference<Map<String, Object>>() {
                    });
            steps.add(new RuleStep(s.path("op").asText(), params, s.path("to").asText()));
        }
        List<String> outputs = loader.mapper().convertValue(
                root.path("outputs"), new TypeReference<List<String>>() {
                });
        return new RuleProgram(steps, outputs);
    }

    /**
     * JSON 规范化深度相等：Number 按数值比较（int/long/double 同值即等），
     * Map 比键集合+递归值，List 逐元素递归，其余按 equals。
     */
    @SuppressWarnings("unchecked")
    static boolean deepEquals(Object expected, Object actual) {
        if (expected instanceof Number en && actual instanceof Number an) {
            return en.doubleValue() == an.doubleValue();
        }
        if (expected instanceof Map<?, ?> em && actual instanceof Map<?, ?> am) {
            if (em.size() != am.size()) {
                return false;
            }
            for (Map.Entry<?, ?> e : em.entrySet()) {
                if (!am.containsKey(e.getKey()) || !deepEquals(e.getValue(), am.get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (expected instanceof List<?> el && actual instanceof List<?> al) {
            if (el.size() != al.size()) {
                return false;
            }
            for (int i = 0; i < el.size(); i++) {
                if (!deepEquals(el.get(i), al.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return java.util.Objects.equals(expected, actual);
    }
}
