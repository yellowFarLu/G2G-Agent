package com.wikiagent.domain.rule.dsl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 确定性规则引擎（规格 §2.3/§D2）：JSON 声明式算子，零 LLM、零随机、零时钟依赖。
 * <p>
 * 【边界标记】{@link com.wikiagent.domain.task.BoundaryType#DETERMINISTIC_TOOL} — 确定性工具，
 * 同一 inputSnapshot+ruleVersion 必得同一 output，无需 Agent 治理拦截。
 * 同一 inputSnapshot+ruleVersion 必得同一 output（AC-D1 重算判定依据）。
 * <p>
 * 支持算子：
 * <ul>
 *   <li>map：{from,to} 复制/重命名值</li>
 *   <li>const：{value,to} 常量注入</li>
 *   <li>arith：{expr,to} 算术表达式（标识符引用输入或中间量）</li>
 *   <li>regex：{field,pattern,to} 正则匹配 → Boolean</li>
 *   <li>enum：{field,allowed:[...],to} 枚举命中 → Boolean</li>
 *   <li>compare：{left,op,right,to} 比较（==/!=/&gt;/&gt;=/&lt;/&lt;=；right 为字面量或 $字段引用）→ Boolean</li>
 *   <li>materialDiff：{left,right,to} 材料 A-B 字段级比对 → [{fieldKey,valueA,valueB,match}]</li>
 * </ul>
 */
public class DeterministicRuleEngine {

    /**
     * 执行规则程序。输入上下文不被修改；产出中间量（输入+每步产出按序）与输出子集。
     *
     * @throws RuleEngineException 定义/执行错误（调用方应记录 FAILED computation）
     */
    public RuleOutcome run(RuleProgram program, Map<String, Object> input) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (input != null) {
            input.forEach((k, v) -> context.put(k, v));
        }
        Map<String, Object> intermediates = new LinkedHashMap<>(context);
        for (RuleStep step : program.steps()) {
            if (step.to() == null || step.to().isBlank()) {
                throw new RuleEngineException("规则步骤缺少产出键 to: " + step.op());
            }
            Object value = execute(step, context);
            context.put(step.to(), value);
            intermediates.put(step.to(), value);
        }
        Map<String, Object> outputs = new LinkedHashMap<>();
        List<String> outputKeys = program.outputs().isEmpty()
                ? program.steps().stream().map(RuleStep::to).toList()
                : program.outputs();
        for (String key : outputKeys) {
            if (!context.containsKey(key)) {
                throw new RuleEngineException("输出键未由任何步骤产出: " + key);
            }
            outputs.put(key, context.get(key));
        }
        return new RuleOutcome(intermediates, outputs);
    }

    private Object execute(RuleStep step, Map<String, Object> context) {
        return switch (step.op()) {
            case RuleStep.OP_MAP -> mapValue(step, context);
            case RuleStep.OP_CONST -> step.params().get("value");
            case RuleStep.OP_ARITH -> arith(step, context);
            case RuleStep.OP_REGEX -> regex(step, context);
            case RuleStep.OP_ENUM -> enumMatch(step, context);
            case RuleStep.OP_COMPARE -> compare(step, context);
            case RuleStep.OP_MATERIAL_DIFF -> materialDiff(step, context);
            default -> throw new RuleEngineException("未知算子: " + step.op()
                    + "（支持: " + RuleStep.SUPPORTED_OPS + "）");
        };
    }

    private Object mapValue(RuleStep step, Map<String, Object> context) {
        String from = step.paramString("from");
        if (from == null) {
            throw new RuleEngineException("map 算子缺少参数 from");
        }
        if (!context.containsKey(from)) {
            throw new RuleEngineException("map 算子引用了不存在的字段: " + from);
        }
        return context.get(from);
    }

    private Object arith(RuleStep step, Map<String, Object> context) {
        String expr = step.paramString("expr");
        if (expr == null || expr.isBlank()) {
            throw new RuleEngineException("arith 算子缺少参数 expr");
        }
        double result = ArithEvaluator.eval(expr, context);
        // 确定性数值表示：整数值输出 long，避免 1.0/1 的表示抖动
        if (result == Math.rint(result) && !Double.isInfinite(result)) {
            return (long) result;
        }
        return result;
    }

    private Object regex(RuleStep step, Map<String, Object> context) {
        String field = required(step, "field");
        String pattern = required(step, "pattern");
        Object value = context.get(field);
        String text = value == null ? null : String.valueOf(value);
        try {
            return text != null && Pattern.compile(pattern).matcher(text).matches();
        } catch (PatternSyntaxException e) {
            throw new RuleEngineException("regex 算子非法正则: " + pattern, e);
        }
    }

    private Object enumMatch(RuleStep step, Map<String, Object> context) {
        String field = required(step, "field");
        List<String> allowed = step.paramStringList("allowed");
        if (allowed.isEmpty()) {
            throw new RuleEngineException("enum 算子缺少参数 allowed");
        }
        Object value = context.get(field);
        return value != null && allowed.contains(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private Object compare(RuleStep step, Map<String, Object> context) {
        String left = required(step, "left");
        String operator = required(step, "op");
        Object leftValue = resolve(left, context);
        Object rightRaw = step.params().get("right");
        Object rightValue = rightRaw instanceof String s && s.startsWith("$")
                ? resolve(s.substring(1), context)
                : rightRaw;
        return switch (operator) {
            case "==" -> Objects.equals(stringify(leftValue), stringify(rightValue));
            case "!=" -> !Objects.equals(stringify(leftValue), stringify(rightValue));
            case ">", ">=", "<", "<=" -> {
                double l = toDouble(leftValue, "left");
                double r = toDouble(rightValue, "right");
                yield switch (operator) {
                    case ">" -> l > r;
                    case ">=" -> l >= r;
                    case "<" -> l < r;
                    default -> l <= r;
                };
            }
            default -> throw new RuleEngineException("compare 算子不支持的操作符: " + operator);
        };
    }

    /**
     * 材料 A-B 比对（规格 §D2）：left/right 为上下文中两个 Map 的键名；
     * 产出字段级差异清单（并集键序稳定：先 A 序后 B 独有按序），每项 {fieldKey,valueA,valueB,match}。
     */
    @SuppressWarnings("unchecked")
    private Object materialDiff(RuleStep step, Map<String, Object> context) {
        String left = required(step, "left");
        String right = required(step, "right");
        Object a = context.get(left);
        Object b = context.get(right);
        if (!(a instanceof Map) || !(b instanceof Map)) {
            throw new RuleEngineException("materialDiff 算子的 left/right 必须是对象(Map): "
                    + left + "/" + right);
        }
        Map<String, Object> mapA = (Map<String, Object>) a;
        Map<String, Object> mapB = (Map<String, Object>) b;
        List<String> keys = new ArrayList<>(mapA.keySet());
        for (String k : mapB.keySet()) {
            if (!keys.contains(k)) {
                keys.add(k);
            }
        }
        List<Map<String, Object>> diffs = new ArrayList<>();
        for (String key : keys) {
            Object va = mapA.get(key);
            Object vb = mapB.get(key);
            boolean match = Objects.equals(stringify(va), stringify(vb));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("fieldKey", key);
            row.put("valueA", va);
            row.put("valueB", vb);
            row.put("match", match);
            diffs.add(row);
        }
        return diffs;
    }

    private Object resolve(String field, Map<String, Object> context) {
        if (!context.containsKey(field)) {
            throw new RuleEngineException("引用了不存在的字段: " + field);
        }
        return context.get(field);
    }

    private static String required(RuleStep step, String key) {
        String v = step.paramString(key);
        if (v == null || v.isBlank()) {
            throw new RuleEngineException(step.op() + " 算子缺少参数 " + key);
        }
        return v;
    }

    private static String stringify(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static double toDouble(Object v, String side) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                throw new RuleEngineException("compare 算子 " + side + " 侧不是数值: " + s, e);
            }
        }
        throw new RuleEngineException("compare 算子 " + side + " 侧类型不支持数值比较: "
                + (v == null ? "null" : v.getClass().getSimpleName()));
    }
}
