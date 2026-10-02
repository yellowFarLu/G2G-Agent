package com.wikiagent.infrastructure.task.mq;

import com.wikiagent.application.task.TaskMessageSink;
import com.wikiagent.config.TaskProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 7 本地投递回路测试：立即投递、延迟投递、看门狗投递均到达 sink 且类型正确。
 */
class LocalDispatchRoundTripTest {

    @Test
    void dispatchesImmediatelyAndWithDelayToSink() throws Exception {
        TaskProperties props = new TaskProperties();
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);
        TaskMessageSink sink = new TaskMessageSink() {
            @Override
            public void onMessage(String taskId, String taskType) {
                received.add(taskId + ":" + taskType);
                latch.countDown();
            }

            @Override
            public void onWatchdog(String taskId, String ownerWorkerId) {
            }
        };
        LocalTaskDispatcher dispatcher = new LocalTaskDispatcher(props, sink) {
            @Override
            protected Duration delayForLevel(int level) {
                return level <= 0 ? Duration.ZERO : Duration.ofMillis(50);
            }
        };
        dispatcher.start();
        try {
            dispatcher.dispatch("t-1", "INGEST", 0);
            dispatcher.dispatch("t-2", "AGENT", 3);
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(received).containsExactlyInAnyOrder("t-1:INGEST", "t-2:AGENT");
        } finally {
            dispatcher.stop();
        }
    }

    @Test
    void watchdogDeliveredAfterLeaseTtl() throws Exception {
        TaskProperties props = new TaskProperties();
        props.setLeaseTtlSec(1);
        CountDownLatch latch = new CountDownLatch(1);
        List<String> watchdogs = new CopyOnWriteArrayList<>();
        TaskMessageSink sink = new TaskMessageSink() {
            @Override
            public void onMessage(String taskId, String taskType) {
            }

            @Override
            public void onWatchdog(String taskId, String ownerWorkerId) {
                watchdogs.add(taskId + ":" + ownerWorkerId);
                latch.countDown();
            }
        };
        LocalTaskDispatcher dispatcher = new LocalTaskDispatcher(props, sink);
        dispatcher.start();
        try {
            dispatcher.dispatchWatchdog("t-3", "w-1", 4);
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(watchdogs).containsExactly("t-3:w-1");
        } finally {
            dispatcher.stop();
        }
    }
}
