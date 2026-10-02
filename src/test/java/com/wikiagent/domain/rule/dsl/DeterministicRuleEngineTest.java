package com.wikiagent.domain.rule.dsl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D 确定性规则引擎纯单元测试：七算子覆盖 + 100 次重算一致性（AC-D1）。
 */
class DeterministicRuleEngineTest {

    private final DeterministicRuleEngine engine = new DeterministicRuleEngine();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mapOp() {
        RuleProgram p = program(List.of(step("map", Map.of("from", "a"), "b")));
        RuleOutcome r = engine.run(p, Map.of("a", "hello"));
        assertThat(r.outputs()).containsEntry("b", "hello");
    }

    @Test
    void constOp() {
        RuleProgram p = program(List.of(step("const", Map.of("value", 42), "x")));
        RuleOutcome r = engine.run(p, Map.of());
        assertThat(r.outputs()).containsEntry("x", 42);
    }

    @Test
    void arithOp() {
        RuleProgram p = program(List.of(step("arith", Map.of("expr", "a + b * 2"), "c")));
        RuleOutcome r = engine.run(p, Map.of("a", 1, "b", 3));
        assertThat(r.outputs()).containsEntry("c", 7L);
    }

    @Test
    void regexOp() {
        RuleProgram p = program(List.of(step("regex", Map.of("field", "phone", "pattern", "\\d{11}"), "valid")));
        RuleOutcome r = engine.run(p, Map.of("phone", "13800138000"));
        assertThat(r.outputs()).containsEntry("valid", true);
    }

    @Test
    void enumOp() {
        RuleProgram p = program(List.of(step("enum", Map.of("field", "color", "allowed", List.of("red", "green")), "ok")));
        RuleOutcome r = engine.run(p, Map.of("color", "red"));
        assertThat(r.outputs()).containsEntry("ok", true);
    }

    @Test
    void compareOp() {
        RuleProgram p = program(List.of(
                step("compare", Map.of("left", "a", "op", ">", "right", 10), "gt")));
        RuleOutcome r = engine.run(p, Map.of("a", 15));
        assertThat(r.outputs()).containsEntry("gt", true);
    }

    @Test
    void materialDiffOp() {
        RuleProgram p = program(List.of(step("materialDiff", Map.of("left", "matA", "right", "matB"), "diff")));
        Map<String, Object> a = Map.of("amount", "100", "tax", "13");
        Map<String, Object> b = Map.of("amount", "120", "tax", "13");
        RuleOutcome r = engine.run(p, Map.of("matA", a, "matB", b));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diff = (List<Map<String, Object>>) r.outputs().get("diff");
        assertThat(diff).hasSize(2);
        assertThat(diff).anyMatch(m -> Boolean.FALSE.equals(m.get("match")));
    }

    @Test
    void hundredRunsProduceIdenticalJson() throws Exception {
        RuleProgram p = program(List.of(
                step("const", Map.of("value", 3.14), "pi"),
                step("arith", Map.of("expr", "pi * 2"), "tau"),
                step("compare", Map.of("left", "tau", "op", ">", "right", 5), "big")));
        Map<String, Object> input = Map.of();
        String first = mapper.writeValueAsString(engine.run(p, input));
        for (int i = 0; i < 99; i++) {
            String current = mapper.writeValueAsString(engine.run(p, input));
            assertThat(current).isEqualTo(first);
        }
    }

    @Test
    void unknownOpThrows() {
        RuleProgram p = program(List.of(step("unknown", Map.of(), "x")));
        assertThatThrownBy(() -> engine.run(p, Map.of()))
                .isInstanceOf(RuleEngineException.class).hasMessageContaining("未知算子");
    }

    @Test
    void missingParamThrows() {
        RuleProgram p = program(List.of(step("map", Map.of(), "x")));
        assertThatThrownBy(() -> engine.run(p, Map.of()))
                .isInstanceOf(RuleEngineException.class).hasMessageContaining("缺少参数");
    }

    @Test
    void divideByZeroThrows() {
        RuleProgram p = program(List.of(step("arith", Map.of("expr", "1 / 0"), "x")));
        assertThatThrownBy(() -> engine.run(p, Map.of()))
                .isInstanceOf(RuleEngineException.class).hasMessageContaining("除零");
    }

    @Test
    void undefinedVariableThrows() {
        RuleProgram p = program(List.of(step("arith", Map.of("expr", "a + 1"), "x")));
        assertThatThrownBy(() -> engine.run(p, Map.of()))
                .isInstanceOf(RuleEngineException.class).hasMessageContaining("未定义");
    }

    private RuleStep step(String op, Map<String, Object> params, String to) {
        return new RuleStep(op, params, to);
    }

    private RuleProgram program(List<RuleStep> steps) {
        return new RuleProgram(steps, List.of());
    }
}
