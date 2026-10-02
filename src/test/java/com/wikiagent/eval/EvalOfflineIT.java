package com.wikiagent.eval;

import com.wikiagent.application.eval.EvalRunner;
import com.wikiagent.domain.eval.EvalCategory;
import com.wikiagent.domain.eval.EvalOptions;
import com.wikiagent.domain.eval.EvalReport;
import com.wikiagent.domain.eval.SampleResult;
import com.wikiagent.domain.eval.metrics.CostPerTurnCalculator;
import com.wikiagent.domain.eval.metrics.FieldAccuracyCalculator;
import com.wikiagent.domain.eval.metrics.HumanEditCalculator;
import com.wikiagent.domain.eval.metrics.JudgeErrorCalculator;
import com.wikiagent.domain.eval.metrics.ResponseTimeCalculator;
import com.wikiagent.domain.eval.metrics.RetrievalHitCalculator;
import com.wikiagent.domain.eval.metrics.CitationCorrectCalculator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * 子项目 H 离线评测端到端集成测试（AC-H2）。
 * <p>
 * 与 {@code AgentEvalTest} 同样的隔离前提：flyway 关闭 + ddl-auto=update，
 * 全 Spring 上下文可在零外部依赖（无 Redis/Milvus/真实 API key）下启动。
 * 评测运行器内部把检索强制到本地关键词降级、OCR/ASR/TABLE 全录制桩。
 * <p>
 * 由 {@code -Peval} profile 的 surefire 显式纳入（默认构建不跑 *IT）。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=update",
        "wikiagent.eval.enabled=true"
})
class EvalOfflineIT {

    /** 独立 H2 文件（flyway 关闭的上下文不得污染默认库，否则后续 flyway 迁移会因列已存在而失败）。 */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:file:./data/h2/test-eval-offline-" + System.nanoTime()
                        + ";AUTO_SERVER=TRUE;MODE=MySQL");
    }

    @Autowired
    private EvalRunner runner;

    @Test
    void 全量离线评测产出七指标报告且黄金样本全通过() throws IOException {
        EvalReport report = runner.runAll(EvalOptions.offlineDefault());

        // ===== 报告元信息 =====
        assertThat(report.profile()).isEqualTo("offline");
        assertThat(report.runId()).isNotBlank();
        assertThat(report.generatedAt()).isNotBlank();
        assertThat(report.baseline()).containsKey("status");

        // ===== 七项指标齐全且有值（AC-H2）=====
        Map<String, ?> summary = report.summary();
        assertThat(summary).containsOnlyKeys(
                FieldAccuracyCalculator.KEY,
                RetrievalHitCalculator.KEY,
                CitationCorrectCalculator.KEY,
                JudgeErrorCalculator.KEY,
                HumanEditCalculator.KEY,
                ResponseTimeCalculator.KEY,
                CostPerTurnCalculator.KEY);

        // 种子 5 字段行 4 有效 1 无效
        assertThat(metricValue(summary, FieldAccuracyCalculator.KEY).value()).isEqualTo(0.8);
        // 6 条 USEFUL 反馈的 RETRIEVED 事件全部命中
        assertThat(metricValue(summary, RetrievalHitCalculator.KEY).value()).isEqualTo(1.0);
        // 全部 Source 可解析且 pageNo/snippet 一致
        assertThat(metricValue(summary, CitationCorrectCalculator.KEY).value()).isEqualTo(1.0);
        // 4 条录制评判：事实错误 2、结构错误 2
        @SuppressWarnings("unchecked")
        Map<String, Object> judgeRates =
                (Map<String, Object>) metricValue(summary, JudgeErrorCalculator.KEY).value();
        assertThat(judgeRates).containsEntry("factErrorRate", 0.5)
                .containsEntry("structureErrorRate", 0.5);
        // 已处置 4 案（APPROVED/REJECTED/EDITED×2），OPEN 不计
        assertThat(metricValue(summary, HumanEditCalculator.KEY).value()).isEqualTo(0.5);
        // 5 条 OK 调用 50/100/200/300/400ms（最近秩法）
        @SuppressWarnings("unchecked")
        Map<String, Object> respValue =
                (Map<String, Object>) metricValue(summary, ResponseTimeCalculator.KEY).value();
        assertThat(respValue).containsEntry("p50", 200L)
                .containsEntry("p95", 400L)
                .containsEntry("sampleCount", 5);
        // 成本 0.105 / 4 个 CHAT 轮
        assertThat((Double) metricValue(summary, CostPerTurnCalculator.KEY).value())
                .isCloseTo(0.105 / 4, offset(1e-12));

        // ===== 样本条数（AC-H1：parse 7 有效+1 skip / retrieve 6 / rule 7 / anomaly 6）=====
        List<SampleResult> results = report.sampleResults();
        assertThat(results).hasSize(27);
        assertThat(count(results, EvalCategory.PARSE)).isEqualTo(8);
        assertThat(count(results, EvalCategory.RETRIEVE)).isEqualTo(6);
        assertThat(count(results, EvalCategory.RULE)).isEqualTo(7);
        assertThat(count(results, EvalCategory.ANOMALY)).isEqualTo(6);
        assertThat(results.stream().filter(SampleResult::skipped).count())
                .as("仅 1 条真实 TIFF 样本跳过").isEqualTo(1);
        List<SampleResult> failures = results.stream()
                .filter(r -> !r.skipped() && !r.passed())
                .toList();
        assertThat(failures)
                .as("全部有效黄金样本必须通过，失败明细：%s", describe(failures))
                .isEmpty();

        // ===== 报告落盘 =====
        Path reportFile = Path.of(EvalOptions.DEFAULT_OUTPUT_DIR, "EvalReport.json");
        assertThat(Files.isRegularFile(reportFile)).isTrue();
        assertThat(Files.size(reportFile)).isPositive();
    }

    private static com.wikiagent.domain.eval.MetricValue metricValue(
            Map<String, ?> summary, String key) {
        Object v = summary.get(key);
        assertThat(v).isInstanceOf(com.wikiagent.domain.eval.MetricValue.class);
        return (com.wikiagent.domain.eval.MetricValue) v;
    }

    private static long count(List<SampleResult> results, EvalCategory category) {
        return results.stream().filter(r -> r.category() == category).count();
    }

    private static String describe(List<SampleResult> failures) {
        return failures.stream()
                .map(f -> f.id() + "[" + (f.error() != null ? f.error() : "") + "]")
                .reduce("", (a, b) -> a + " " + b);
    }
}
