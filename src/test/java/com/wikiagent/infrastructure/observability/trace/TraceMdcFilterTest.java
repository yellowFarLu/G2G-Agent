package com.wikiagent.infrastructure.observability.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-I1 {@link TraceMdcFilter} 单测（standalone MockMvc，不起 Spring 容器）：
 * 头透传/缺省生成/响应回写/控制器内 MDC 可见/请求结束 MDC 清理。
 */
class TraceMdcFilterTest {

    static final AtomicReference<String> mdcInController = new AtomicReference<>();

    @RestController
    static class ProbeController {
        @GetMapping("/probe")
        String probe() {
            mdcInController.set(MDC.get("traceId"));
            return "ok";
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .addFilters(new TraceMdcFilter())
            .build();

    @AfterEach
    void clear() {
        MDC.clear();
        mdcInController.set(null);
    }

    @Test
    void 携带XTraceId时原样透传并回写() throws Exception {
        mvc.perform(get("/probe").header(TraceMdcFilter.TRACE_HEADER, "upstream-tr-1"))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceMdcFilter.TRACE_HEADER, "upstream-tr-1"));
        assertThat(mdcInController.get()).isEqualTo("upstream-tr-1");
        // 请求线程（同测试线程）MDC 已清理
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    void 无头时生成tr前缀traceId并回写() throws Exception {
        mvc.perform(get("/probe"))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceMdcFilter.TRACE_HEADER, org.hamcrest.Matchers.startsWith("tr-")));
        assertThat(mdcInController.get()).startsWith("tr-");
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    void 控制器抛异常时MDC仍被清理() throws Exception {
        MockMvc mvc2 = MockMvcBuilders.standaloneSetup(new BoomController())
                .addFilters(new TraceMdcFilter()).build();
        try {
            mvc2.perform(get("/boom"));
        } catch (Exception ignored) {
            // 控制器异常被 standalone 容器包装，断言重点是 MDC 清理
        }
        assertThat(MDC.get("traceId")).isNull();
    }

    @RestController
    static class BoomController {
        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("boom");
        }
    }
}
