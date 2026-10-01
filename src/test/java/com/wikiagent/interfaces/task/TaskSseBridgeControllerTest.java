package com.wikiagent.interfaces.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.application.task.StreamEvent;
import com.wikiagent.application.task.TaskStreamBus;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Task 12 SSE 桥控制器单元测试：GET /api/tasks/{taskId}/stream 订阅 TaskStreamBus，
 * 事件原样转发为 SSE（delta/done/error/progress），done/error 后完成并注销订阅。
 */
class TaskSseBridgeControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 捕获订阅的假 bus。 */
    static class FakeBus implements TaskStreamBus {
        Consumer<StreamEvent> listener;
        volatile boolean closed;

        @Override
        public void publish(StreamEvent event) {
        }

        @Override
        public AutoCloseable subscribe(String taskId, Consumer<StreamEvent> l) {
            this.listener = l;
            return () -> {
                closed = true;
            };
        }
    }

    @Test
    void bridgeForwardsEventsAndClosesOnDone() throws Exception {
        FakeBus bus = new FakeBus();
        TaskSseBridgeController controller = new TaskSseBridgeController(bus);
        MockMvc mvc = standaloneSetup(controller).build();

        MvcResult started = mvc.perform(get("/api/tasks/tsk-bridge-1/stream"))
                .andExpect(request().asyncStarted())
                .andReturn();

        bus.listener.accept(new StreamEvent("tsk-bridge-1", "delta",
                JSON.createObjectNode().put("text", "你好")));
        bus.listener.accept(new StreamEvent("tsk-bridge-1", "done",
                JSON.createObjectNode().put("status", "COMPLETED")));

        MvcResult done = mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn();
        String content = done.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(content).contains("delta").contains("你好")
                .contains("done").contains("COMPLETED");
        assertThat(bus.closed).isTrue();
    }

    @Test
    void bridgeClosesOnErrorToo() throws Exception {
        FakeBus bus = new FakeBus();
        MockMvc mvc = standaloneSetup(new TaskSseBridgeController(bus)).build();

        MvcResult started = mvc.perform(get("/api/tasks/tsk-bridge-2/stream"))
                .andExpect(request().asyncStarted())
                .andReturn();

        bus.listener.accept(new StreamEvent("tsk-bridge-2", "error",
                JSON.createObjectNode().put("errorCode", "INTERNAL").put("msg", "boom")));

        MvcResult failed = mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(failed.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("error").contains("boom");
        assertThat(bus.closed).isTrue();
    }
}
