package com.wikiagent.infrastructure.task.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.application.task.OptimisticControlConflictException;
import com.wikiagent.application.task.StreamEvent;
import com.wikiagent.application.task.TaskStreamBus;
import com.wikiagent.domain.task.ControlFlag;
import com.wikiagent.infrastructure.task.jvm.InProcessTaskStreamBus;
import com.wikiagent.infrastructure.task.jvm.JvmControlFlagPort;
import com.wikiagent.infrastructure.task.jvm.JvmLeasePort;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 6 协调端口语义测试：用 JVM 降级实现验证端口契约（Redis 实现仅在 profile=it 跑真 Redis）。
 */
class RedisBackedPortsTest {

    private final JvmLeasePort leasePort = new JvmLeasePort();
    private final JvmControlFlagPort controlPort = new JvmControlFlagPort();

    // ---- LeasePort ----

    @Test
    void leaseIsExclusiveAndReleasable() {
        assertThat(leasePort.tryAcquire("t1", "w1", Duration.ofSeconds(30))).isTrue();
        assertThat(leasePort.tryAcquire("t1", "w2", Duration.ofSeconds(30))).isFalse();

        // 非 owner release 不生效：租约仍被 w1 持有
        leasePort.release("t1", "w2");
        assertThat(leasePort.tryAcquire("t1", "w2", Duration.ofSeconds(30))).isFalse();

        // owner release 后可再获取
        leasePort.release("t1", "w1");
        assertThat(leasePort.tryAcquire("t1", "w2", Duration.ofSeconds(30))).isTrue();
    }

    // ---- ControlFlagPort ----

    @Test
    void pauseSetsFlagAndBumpsVersion() {
        controlPort.requestPause("t2", 0);
        assertThat(controlPort.read("t2")).isEqualTo(ControlFlag.PAUSE);
        assertThat(controlPort.currentVersion("t2")).isEqualTo(1);
    }

    @Test
    void staleExpectedVersionConflicts() {
        controlPort.requestPause("t3", 0);
        assertThatThrownBy(() -> controlPort.requestPause("t3", 0))
                .isInstanceOf(OptimisticControlConflictException.class);
        // 版本推进后可继续
        controlPort.requestCancel("t3", 1);
        assertThat(controlPort.read("t3")).isEqualTo(ControlFlag.CANCEL);
        assertThat(controlPort.currentVersion("t3")).isEqualTo(2);
    }

    @Test
    void clearRemovesFlag() {
        controlPort.requestCancel("t4", 0);
        assertThat(controlPort.read("t4")).isEqualTo(ControlFlag.CANCEL);
        controlPort.clear("t4");
        assertThat(controlPort.read("t4")).isEqualTo(ControlFlag.NONE);
        assertThat(controlPort.currentVersion("t4")).isZero();
    }

    // ---- TaskStreamBus（InProcess 实现）----

    @Test
    void streamBusDeliversOnlyToSameTaskSubscriber() throws Exception {
        TaskStreamBus bus = new InProcessTaskStreamBus();
        ObjectMapper mapper = new ObjectMapper();

        List<StreamEvent> received = new ArrayList<>();
        AutoCloseable sub = bus.subscribe("t5", received::add);
        AtomicInteger otherTask = new AtomicInteger();
        AutoCloseable other = bus.subscribe("t6", e -> otherTask.incrementAndGet());

        bus.publish(new StreamEvent("t5", "progress",
                mapper.readTree("{\"percent\":50}")));
        bus.publish(new StreamEvent("t6", "delta",
                mapper.readTree("{\"text\":\"hi\"}")));

        assertThat(received).hasSize(1);
        assertThat(received.get(0).taskId()).isEqualTo("t5");
        assertThat(received.get(0).type()).isEqualTo("progress");
        assertThat(received.get(0).payload().get("percent").asInt()).isEqualTo(50);
        assertThat(otherTask.get()).isEqualTo(1);

        // close 后不再收到
        sub.close();
        bus.publish(new StreamEvent("t5", "done", mapper.nullNode()));
        assertThat(received).hasSize(1);
        other.close();
    }
}
