package com.wikiagent.domain.task;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F2 TaskBudget 单元测试：系统封顶、超限判定、人工提额、序列化/反序列化。
 */
class TaskBudgetTest {

    @Test
    void defaultBudgetMatchesSystemCap() {
        TaskBudget b = TaskBudget.defaultBudget();
        assertThat(b.tokenLimit()).isEqualTo(TaskBudget.SYSTEM_TOKEN_CAP);
        assertThat(b.costLimit()).isEqualTo(TaskBudget.SYSTEM_COST_CAP);
        assertThat(b.iterationLimit()).isEqualTo(TaskBudget.SYSTEM_ITERATION_CAP);
        assertThat(b.tokenUsed()).isZero();
        assertThat(b.costUsed()).isZero();
        assertThat(b.iterationUsed()).isZero();
        assertThat(b.overflowPolicy()).isEqualTo(TaskBudget.OverflowPolicy.PAUSE_HUMAN);
    }

    @Test
    void usageAccumulates() {
        TaskBudget b = TaskBudget.defaultBudget().withUsage(100, 0.001);
        assertThat(b.tokenUsed()).isEqualTo(100);
        assertThat(b.costUsed()).isPositive();
        assertThat(b.iterationUsed()).isEqualTo(1);
    }

    @Test
    void exceededByIteration() {
        TaskBudget b = new TaskBudget(0, 100, 0, 10, 3, 3, TaskBudget.OverflowPolicy.FAIL);
        assertThat(b.exceededBy()).isEqualTo("ITERATION_LIMIT(3>=3)");
    }

    @Test
    void exceededByToken() {
        TaskBudget b = new TaskBudget(100, 100, 0, 10, 0, 10, TaskBudget.OverflowPolicy.FAIL);
        assertThat(b.exceededBy()).startsWith("TOKEN_LIMIT");
    }

    @Test
    void exceededByCost() {
        TaskBudget b = new TaskBudget(0, 100, 5.0, 5.0, 0, 10, TaskBudget.OverflowPolicy.FAIL);
        assertThat(b.exceededBy()).startsWith("COST_LIMIT");
    }

    @Test
    void capPreventsExceedingSystemCeiling() {
        TaskBudget b = new TaskBudget(0, 99999, 0, 99999, 0, 99999, TaskBudget.OverflowPolicy.PAUSE_HUMAN);
        assertThat(b.tokenLimit()).isEqualTo(TaskBudget.SYSTEM_TOKEN_CAP);
        assertThat(b.costLimit()).isEqualTo(TaskBudget.SYSTEM_COST_CAP);
        assertThat(b.iterationLimit()).isEqualTo(TaskBudget.SYSTEM_ITERATION_CAP);
    }

    @Test
    void raiseLimitsAndCap() {
        TaskBudget b = TaskBudget.defaultBudget().withLimits(100L, 1.0, 2);
        assertThat(b.tokenLimit()).isEqualTo(100);
        assertThat(b.costLimit()).isEqualTo(1.0);
        assertThat(b.iterationLimit()).isEqualTo(2);
    }

    @Test
    void raiseLimitsOverSystemCapGetsClamped() {
        TaskBudget b = TaskBudget.defaultBudget().withLimits(99999L, 999.0, 999);
        assertThat(b.tokenLimit()).isEqualTo(TaskBudget.SYSTEM_TOKEN_CAP);
        assertThat(b.costLimit()).isEqualTo(TaskBudget.SYSTEM_COST_CAP);
        assertThat(b.iterationLimit()).isEqualTo(TaskBudget.SYSTEM_ITERATION_CAP);
    }

    @Test
    void serializeRoundTrip() {
        TaskBudget original = new TaskBudget(50, 100, 0.5, 2.0, 3, 5, TaskBudget.OverflowPolicy.FAIL);
        TaskBudget restored = TaskBudget.fromJson(original.toJson());
        assertThat(restored).isEqualTo(original);
    }

    @Test
    void deserializeNullReturnsDefault() {
        assertThat(TaskBudget.fromJson(null)).isEqualTo(TaskBudget.defaultBudget());
    }

    @Test
    void deserializeMissingFieldsUsesSystemCap() {
        // 仅含已用量的对象，上限缺失→系统封顶
        var node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                .put("tokenUsed", 100)
                .put("iterationUsed", 1);
        TaskBudget b = TaskBudget.fromJson(node);
        assertThat(b.tokenLimit()).isEqualTo(TaskBudget.SYSTEM_TOKEN_CAP);
        assertThat(b.costLimit()).isEqualTo(TaskBudget.SYSTEM_COST_CAP);
        assertThat(b.iterationLimit()).isEqualTo(TaskBudget.SYSTEM_ITERATION_CAP);
        assertThat(b.overflowPolicy()).isEqualTo(TaskBudget.OverflowPolicy.PAUSE_HUMAN);
    }
}
