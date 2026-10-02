package com.wikiagent.domain.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 子项目 A Task 2：任务级状态机测试。
 * 合法迁移逐行对应设计规格 1.2 转移表（WAIT_HUMAN 事件驱动 RUNNING→WAITING_HUMAN）；
 * 非法迁移对全部 (from, event) 组合穷举断言抛 {@link IllegalStateTransitionException}。
 */
class TaskStateMachineTest {

    /** 测试用迁移键（from+event），用于穷举非法迁移。 */
    private record Transition(TaskStatus from, TaskEventType event) {
    }

    /** 规格表行：from + event → to（from=null 表示新建）。 */
    private record Row(TaskStatus from, TaskEventType event, TaskStatus to) {
    }

    /** 规格 1.2 全部合法迁移（唯一事实来源，合法/非法两组测试都由它派生）。 */
    private static final List<Row> SPEC_12_ROWS = List.of(
            new Row(null, TaskEventType.SUBMIT, TaskStatus.PENDING),
            new Row(TaskStatus.PENDING, TaskEventType.DISPATCH, TaskStatus.DISPATCH),
            new Row(TaskStatus.PENDING, TaskEventType.LEASE, TaskStatus.RUNNING),
            new Row(TaskStatus.DISPATCH, TaskEventType.LEASE, TaskStatus.RUNNING),
            new Row(TaskStatus.RUNNING, TaskEventType.SUSPEND, TaskStatus.SUSPENDED),
            new Row(TaskStatus.RUNNING, TaskEventType.WAIT_HUMAN, TaskStatus.WAITING_HUMAN),
            new Row(TaskStatus.SUSPENDED, TaskEventType.RESUME, TaskStatus.PENDING),
            new Row(TaskStatus.WAITING_HUMAN, TaskEventType.RESUME, TaskStatus.PENDING),
            new Row(TaskStatus.WAITING_HUMAN, TaskEventType.HUMAN_RESOLVE, TaskStatus.COMPLETED),
            new Row(TaskStatus.RUNNING, TaskEventType.REQUEST_CANCEL, TaskStatus.CANCELING),
            new Row(TaskStatus.SUSPENDED, TaskEventType.REQUEST_CANCEL, TaskStatus.CANCELING),
            new Row(TaskStatus.CANCELING, TaskEventType.CANCEL, TaskStatus.CANCELLED),
            new Row(TaskStatus.RUNNING, TaskEventType.RETRY, TaskStatus.PENDING),
            new Row(TaskStatus.RUNNING, TaskEventType.COMPLETE, TaskStatus.COMPLETED),
            new Row(TaskStatus.RUNNING, TaskEventType.FAIL, TaskStatus.FAILED),
            new Row(TaskStatus.FAILED, TaskEventType.REPLAY, TaskStatus.PENDING),
            new Row(TaskStatus.CANCELLED, TaskEventType.REPLAY, TaskStatus.PENDING));

    static Stream<Arguments> legalTransitions() {
        return SPEC_12_ROWS.stream().map(row -> Arguments.arguments(row.from(), row.event(), row.to()));
    }

    @ParameterizedTest(name = "合法迁移 {0} + {1} → {2}")
    @MethodSource("legalTransitions")
    void legalTransitionSucceeds(TaskStatus from, TaskEventType event, TaskStatus expectedTo) {
        assertThat(TaskStateMachine.transition(from, event)).isEqualTo(expectedTo);
    }

    static Stream<Arguments> illegalTransitions() {
        Set<Transition> legal = new HashSet<>();
        SPEC_12_ROWS.forEach(row -> legal.add(new Transition(row.from(), row.event())));

        List<Arguments> illegal = new ArrayList<>();
        for (TaskStatus from : TaskStatus.values()) {
            for (TaskEventType event : TaskEventType.values()) {
                if (!legal.contains(new Transition(from, event))) {
                    illegal.add(Arguments.arguments(from, event));
                }
            }
        }
        for (TaskEventType event : TaskEventType.values()) {
            if (event != TaskEventType.SUBMIT) {
                illegal.add(Arguments.arguments((TaskStatus) null, event));
            }
        }
        return illegal.stream();
    }

    @ParameterizedTest(name = "非法迁移 {0} + {1} 应抛 IllegalStateTransitionException")
    @MethodSource("illegalTransitions")
    void illegalTransitionThrows(TaskStatus from, TaskEventType event) {
        assertThatThrownBy(() -> TaskStateMachine.transition(from, event))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining(String.valueOf(from))
                .hasMessageContaining(String.valueOf(event));
    }

    @Test
    void completedRejectsEveryEvent() {
        for (TaskEventType event : TaskEventType.values()) {
            assertThatThrownBy(() -> TaskStateMachine.transition(TaskStatus.COMPLETED, event))
                    .as("COMPLETED 应拒绝事件 %s", event)
                    .isInstanceOf(IllegalStateTransitionException.class);
        }
    }

    @Test
    void assertInitialMapsSubmitToPending() {
        assertThat(TaskStateMachine.assertInitial(TaskEventType.SUBMIT)).isEqualTo(TaskStatus.PENDING);
    }

    static Stream<TaskEventType> nonSubmitEvents() {
        return Arrays.stream(TaskEventType.values()).filter(event -> event != TaskEventType.SUBMIT);
    }

    @ParameterizedTest(name = "新建阶段事件 {0} 应抛 IllegalStateTransitionException")
    @MethodSource("nonSubmitEvents")
    void assertInitialRejectsNonSubmitEvents(TaskEventType event) {
        assertThatThrownBy(() -> TaskStateMachine.assertInitial(event))
                .isInstanceOf(IllegalStateTransitionException.class);
        assertThatThrownBy(() -> TaskStateMachine.transition(null, event))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    static Stream<Arguments> leaseStates() {
        return Stream.of(
                Arguments.arguments(TaskStatus.PENDING, true),
                Arguments.arguments(TaskStatus.DISPATCH, true),
                Arguments.arguments(TaskStatus.RUNNING, false),
                Arguments.arguments(TaskStatus.SUSPENDED, false),
                Arguments.arguments(TaskStatus.WAITING_HUMAN, false),
                Arguments.arguments(TaskStatus.CANCELING, false),
                Arguments.arguments(TaskStatus.COMPLETED, false),
                Arguments.arguments(TaskStatus.FAILED, false),
                Arguments.arguments(TaskStatus.CANCELLED, false));
    }

    @ParameterizedTest(name = "canLease({0}) = {1}")
    @MethodSource("leaseStates")
    void canLeaseOnlyForPendingAndDispatch(TaskStatus status, boolean expected) {
        assertThat(TaskStateMachine.canLease(status)).isEqualTo(expected);
    }

    static Stream<Arguments> terminalStates() {
        return Stream.of(
                Arguments.arguments(TaskStatus.PENDING, false),
                Arguments.arguments(TaskStatus.DISPATCH, false),
                Arguments.arguments(TaskStatus.RUNNING, false),
                Arguments.arguments(TaskStatus.SUSPENDED, false),
                Arguments.arguments(TaskStatus.WAITING_HUMAN, false),
                Arguments.arguments(TaskStatus.CANCELING, false),
                Arguments.arguments(TaskStatus.COMPLETED, true),
                Arguments.arguments(TaskStatus.FAILED, true),
                Arguments.arguments(TaskStatus.CANCELLED, true));
    }

    @ParameterizedTest(name = "isTerminal({0}) = {1}")
    @MethodSource("terminalStates")
    void onlyCompletedFailedCancelledAreTerminal(TaskStatus status, boolean expected) {
        assertThat(status.isTerminal()).isEqualTo(expected);
    }

    @Test
    void exceptionCarriesFromAndEvent() {
        IllegalStateTransitionException exception =
                new IllegalStateTransitionException(TaskStatus.RUNNING, TaskEventType.SUBMIT);
        assertThat(exception.getFrom()).isEqualTo(TaskStatus.RUNNING);
        assertThat(exception.getEvent()).isEqualTo(TaskEventType.SUBMIT);
    }
}
