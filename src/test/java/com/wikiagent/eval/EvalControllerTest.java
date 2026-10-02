package com.wikiagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.application.eval.EvalReportStore;
import com.wikiagent.application.eval.EvalRunner;
import com.wikiagent.domain.eval.EvalReport;
import com.wikiagent.domain.eval.MetricValue;
import com.wikiagent.interfaces.eval.EvalController;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * H4 EvalController 切片测试（standalone MockMvc，不起 Spring 全上下文）：
 * latest 200 / 指定文件 200 / 缺失 404 / 路径穿越 400 / 评测未启用 POST 503。
 */
class EvalControllerTest {

    private static final String TEST_DIR = "target/eval-controller-test";

    private static MockMvc mvc;
    private static EvalReportStore store;

    @BeforeAll
    static void setUp() {
        store = new EvalReportStore(TEST_DIR, new ObjectMapper());
        Map<String, MetricValue> summary = new LinkedHashMap<>();
        summary.put("fieldAccuracyRate", MetricValue.of("fieldAccuracyRate", 0.8));
        EvalReport report = new EvalReport("2026-03-01T00:00:00Z", "eval-it", null,
                "offline", summary, List.of(), Map.of());
        store.write(report);

        @SuppressWarnings("unchecked")
        ObjectProvider<EvalRunner> absentRunner = mock(ObjectProvider.class);
        when(absentRunner.getIfAvailable()).thenReturn(null);

        mvc = MockMvcBuilders.standaloneSetup(new EvalController(store, absentRunner)).build();
    }

    @Test
    void latest返回200与报告体() throws Exception {
        mvc.perform(get("/api/eval/report/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("eval-it"))
                .andExpect(jsonPath("$.profile").value("offline"))
                .andExpect(jsonPath("$.summary.fieldAccuracyRate.value").value(0.8));
    }

    @Test
    void 指定文件名返回200() throws Exception {
        mvc.perform(get("/api/eval/report/EvalReport.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.runId").value("eval-it"));
    }

    @Test
    void 报告不存在返回404() throws Exception {
        mvc.perform(get("/api/eval/report/not-exist.json"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 路径穿越文件名返回400() throws Exception {
        // %2F 解码后为 ../evil.json：白名单字符校验失败（即使构造穿越路径也到不了磁盘）
        mvc.perform(get("/api/eval/report/..%2Fevil.json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 评测未启用时POST运行返回503() throws Exception {
        mvc.perform(post("/api/eval/run"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").exists());
    }
}
