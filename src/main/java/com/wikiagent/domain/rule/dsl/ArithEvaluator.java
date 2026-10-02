package com.wikiagent.domain.rule.dsl;

import java.util.Map;

/**
 * 确定性算术表达式求值器（规格 §D2 arith 算子）：
 * 支持 + - * / 与括号、数值字面量、标识符（从上下文取值，数值型字符串自动转换）。
 * 递归下降实现，零随机零外部依赖，同一表达式+同一上下文必得同一结果。
 */
final class ArithEvaluator {

    private final String expr;
    private final Map<String, Object> context;
    private int pos;

    private ArithEvaluator(String expr, Map<String, Object> context) {
        this.expr = expr;
        this.context = context;
        this.pos = 0;
    }

    static double eval(String expr, Map<String, Object> context) {
        ArithEvaluator evaluator = new ArithEvaluator(expr, context);
        double value = evaluator.parseExpr();
        evaluator.skipWhitespace();
        if (evaluator.pos != evaluator.expr.length()) {
            throw new RuleEngineException("算术表达式存在无法解析的尾部: " + expr.substring(evaluator.pos));
        }
        return value;
    }

    private double parseExpr() {
        double left = parseTerm();
        while (true) {
            skipWhitespace();
            if (match('+')) {
                left = left + parseTerm();
            } else if (match('-')) {
                left = left - parseTerm();
            } else {
                return left;
            }
        }
    }

    private double parseTerm() {
        double left = parseFactor();
        while (true) {
            skipWhitespace();
            if (match('*')) {
                left = left * parseFactor();
            } else if (match('/')) {
                double divisor = parseFactor();
                if (divisor == 0d) {
                    throw new RuleEngineException("算术表达式除零: " + expr);
                }
                left = left / divisor;
            } else {
                return left;
            }
        }
    }

    private double parseFactor() {
        skipWhitespace();
        if (match('-')) {
            return -parseFactor();
        }
        if (match('(')) {
            double inner = parseExpr();
            skipWhitespace();
            if (!match(')')) {
                throw new RuleEngineException("算术表达式缺少右括号: " + expr);
            }
            return inner;
        }
        if (pos < expr.length() && (Character.isDigit(expr.charAt(pos)) || expr.charAt(pos) == '.')) {
            return parseNumber();
        }
        return parseIdentifier();
    }

    private double parseNumber() {
        int start = pos;
        while (pos < expr.length() && (Character.isDigit(expr.charAt(pos)) || expr.charAt(pos) == '.')) {
            pos++;
        }
        try {
            return Double.parseDouble(expr.substring(start, pos));
        } catch (NumberFormatException e) {
            throw new RuleEngineException("非法数值字面量: " + expr.substring(start, pos), e);
        }
    }

    private double parseIdentifier() {
        int start = pos;
        while (pos < expr.length()
                && (Character.isLetterOrDigit(expr.charAt(pos)) || expr.charAt(pos) == '_' || expr.charAt(pos) == '.')) {
            pos++;
        }
        if (start == pos) {
            throw new RuleEngineException("算术表达式在位置 " + pos + " 处缺少操作数: " + expr);
        }
        String name = expr.substring(start, pos);
        Object value = context.get(name);
        if (value == null) {
            throw new RuleEngineException("算术表达式引用未定义的变量: " + name);
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                throw new RuleEngineException("变量 " + name + " 的值不是数值: " + s, e);
            }
        }
        throw new RuleEngineException("变量 " + name + " 的类型不支持算术运算: " + value.getClass().getSimpleName());
    }

    private boolean match(char c) {
        if (pos < expr.length() && expr.charAt(pos) == c) {
            pos++;
            return true;
        }
        return false;
    }

    private void skipWhitespace() {
        while (pos < expr.length() && Character.isWhitespace(expr.charAt(pos))) {
            pos++;
        }
    }
}
