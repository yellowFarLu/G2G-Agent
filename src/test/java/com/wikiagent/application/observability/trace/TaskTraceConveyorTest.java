package com.wikiagent.application.observability.trace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-I1 {@link TaskTraceConveyor} 单测：绑定/一次性消费/空值忽略。
 */
class TaskTraceConveyorTest {

    @Test
    void bind后consume得到traceId且一次性() {
        TaskTraceConveyor conveyor = new TaskTraceConveyor();
        conveyor.bind("task-1", "tr-abc");
        assertThat(conveyor.consume("task-1")).isEqualTo("tr-abc");
        assertThat(conveyor.consume("task-1")).isNull();
    }

    @Test
    void 覆盖绑定以最新值为准_retry同链场景() {
        TaskTraceConveyor conveyor = new TaskTraceConveyor();
        conveyor.bind("task-1", "tr-old");
        conveyor.bind("task-1", "tr-new");
        assertThat(conveyor.consume("task-1")).isEqualTo("tr-new");
    }

    @Test
    void 空taskId或空traceId忽略() {
        TaskTraceConveyor conveyor = new TaskTraceConveyor();
        conveyor.bind(null, "tr-x");
        conveyor.bind("task-1", null);
        conveyor.bind("task-1", "  ");
        assertThat(conveyor.size()).isZero();
        assertThat(conveyor.consume("task-1")).isNull();
        assertThat(conveyor.consume(null)).isNull();
    }

    @Test
    void 未绑定任务consume返回null() {
        assertThat(new TaskTraceConveyor().consume("nope")).isNull();
    }
}
