package com.wikiagent.domain.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 子项目 F2：任务预算（token / cost / maxIterations 三维计量 + 超限策略）。
 * <p>
 * 不可变值对象，序列化进 TaskInstance.payload 的 {@code "budget"} 字段，
 * worker 断点恢复时从 payload 读回（用量不重置）。
 * <p>
 * 系统上限封顶：payload 未设或设得更高时，有效上限一律夹到系统常量
 * （tokenLimit=8192 / costLimit=10.0 USD / iterationLimit=20）。
 */
public record TaskBudget(
        long tokenUsed,
        long tokenLimit,
        double costUsed,
        double costLimit,
        int iterationUsed,
        int iterationLimit,
        OverflowPolicy overflowPolicy) {

    /** 超限策略：PAUSE_HUMAN 转人工接管（INPUT）；FAIL 致命失败 BUDGET_EXCEEDED。 */
    public enum OverflowPolicy {
        PAUSE_HUMAN,
        FAIL
    }

    /** 系统封顶：任何来源（payload / 人工调整）都不得突破。 */
    public static final long SYSTEM_TOKEN_CAP = 8192L;
    public static final double SYSTEM_COST_CAP = 10.0d;
    public static final int SYSTEM_ITERATION_CAP = 20;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 紧凑构造器：上限一律夹到系统封顶（未设/越界同语义）。 */
    public TaskBudget {
        tokenLimit = capToken(tokenLimit);
        costLimit = capCost(costLimit);
        iterationLimit = capIteration(iterationLimit);
        if (overflowPolicy == null) {
            overflowPolicy = OverflowPolicy.PAUSE_HUMAN;
        }
    }

    /** 默认预算：用量 0，上限=系统封顶，策略 PAUSE_HUMAN。 */
    public static TaskBudget defaultBudget() {
        return new TaskBudget(0, SYSTEM_TOKEN_CAP, 0, SYSTEM_COST_CAP, 0,
                SYSTEM_ITERATION_CAP, OverflowPolicy.PAUSE_HUMAN);
    }

    /** 记账：+tokens、+cost、+1 iteration。 */
    public TaskBudget withUsage(long tokens, double cost) {
        return new TaskBudget(tokenUsed + Math.max(0, tokens), tokenLimit,
                costUsed + Math.max(0, cost), costLimit,
                iterationUsed + 1, iterationLimit, overflowPolicy);
    }

    /** 调整上限（人工接管表单提额）；仍然夹到系统封顶。 */
    public TaskBudget withLimits(Long newTokenLimit, Double newCostLimit, Integer newIterationLimit) {
        return new TaskBudget(tokenUsed, capToken(newTokenLimit != null ? newTokenLimit : tokenLimit),
                costUsed, capCost(newCostLimit != null ? newCostLimit : costLimit),
                iterationUsed, capIteration(newIterationLimit != null ? newIterationLimit : iterationLimit),
                overflowPolicy);
    }

    /** 超限检查：未超限返回 null；否则返回超限维度标识（TOKEN_LIMIT / COST_LIMIT / ITERATION_LIMIT）。 */
    public String exceededBy() {
        if (tokenUsed >= tokenLimit) {
            return "TOKEN_LIMIT(" + tokenUsed + ">=" + tokenLimit + ")";
        }
        if (costUsed >= costLimit) {
            return "COST_LIMIT(" + costUsed + ">=" + costLimit + ")";
        }
        if (iterationUsed >= iterationLimit) {
            return "ITERATION_LIMIT(" + iterationUsed + ">=" + iterationLimit + ")";
        }
        return null;
    }

    /** 序列化为 JSON 对象（写入 payload.budget）。 */
    public ObjectNode toJson() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("tokenUsed", tokenUsed);
        node.put("tokenLimit", tokenLimit);
        node.put("costUsed", costUsed);
        node.put("costLimit", costLimit);
        node.put("iterationUsed", iterationUsed);
        node.put("iterationLimit", iterationLimit);
        node.put("overflowPolicy", overflowPolicy.name());
        return node;
    }

    /** 从 payload.budget 反序列化；缺失字段取系统封顶（封顶语义见类注释）。 */
    public static TaskBudget fromJson(JsonNode node) {
        if (node == null || !node.isObject()) {
            return defaultBudget();
        }
        long tokenUsed = node.path("tokenUsed").asLong(0);
        double costUsed = node.path("costUsed").asDouble(0);
        int iterationUsed = node.path("iterationUsed").asInt(0);
        long tokenLimit = node.hasNonNull("tokenLimit")
                ? capToken(node.get("tokenLimit").asLong()) : SYSTEM_TOKEN_CAP;
        double costLimit = node.hasNonNull("costLimit")
                ? capCost(node.get("costLimit").asDouble()) : SYSTEM_COST_CAP;
        int iterationLimit = node.hasNonNull("iterationLimit")
                ? capIteration(node.get("iterationLimit").asInt()) : SYSTEM_ITERATION_CAP;
        OverflowPolicy policy = OverflowPolicy.PAUSE_HUMAN;
        String p = node.path("overflowPolicy").asText(null);
        if (p != null) {
            try {
                policy = OverflowPolicy.valueOf(p);
            } catch (IllegalArgumentException ignored) {
                // 未知策略回退默认 PAUSE_HUMAN
            }
        }
        return new TaskBudget(tokenUsed, tokenLimit, costUsed, costLimit,
                iterationUsed, iterationLimit, policy);
    }

    private static long capToken(long v) {
        return v <= 0 ? SYSTEM_TOKEN_CAP : Math.min(v, SYSTEM_TOKEN_CAP);
    }

    private static double capCost(double v) {
        return v <= 0 ? SYSTEM_COST_CAP : Math.min(v, SYSTEM_COST_CAP);
    }

    private static int capIteration(int v) {
        return v <= 0 ? SYSTEM_ITERATION_CAP : Math.min(v, SYSTEM_ITERATION_CAP);
    }
}
