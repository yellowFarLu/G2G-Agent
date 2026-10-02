package com.wikiagent.application.eval;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.application.eval.metrics.JpaEvalDataSource;
import com.wikiagent.application.eval.suite.AnomalyEvalSuite;
import com.wikiagent.application.eval.suite.ParseEvalSuite;
import com.wikiagent.application.eval.suite.RetrieveEvalSuite;
import com.wikiagent.application.eval.suite.RuleEvalSuite;
import com.wikiagent.application.eval.suite.SuiteOutput;
import com.wikiagent.domain.eval.EvalOptions;
import com.wikiagent.domain.eval.EvalReport;
import com.wikiagent.domain.eval.EvalWindow;
import com.wikiagent.domain.eval.MetricValue;
import com.wikiagent.domain.eval.SampleResult;
import com.wikiagent.domain.eval.data.CitationSample;
import com.wikiagent.domain.eval.data.JudgeVerdict;
import com.wikiagent.domain.eval.metrics.CitationCorrectCalculator;
import com.wikiagent.domain.eval.metrics.CostPerTurnCalculator;
import com.wikiagent.domain.eval.metrics.FieldAccuracyCalculator;
import com.wikiagent.domain.eval.metrics.HumanEditCalculator;
import com.wikiagent.domain.eval.metrics.JudgeErrorCalculator;
import com.wikiagent.domain.eval.metrics.MetricCalculator;
import com.wikiagent.domain.eval.metrics.ResponseTimeCalculator;
import com.wikiagent.domain.eval.metrics.RetrievalHitCalculator;
import com.wikiagent.infrastructure.persistence.KbFeedbackJpaDao;
import com.wikiagent.infrastructure.persistence.KnowledgeMetadataJpaDao;
import com.wikiagent.infrastructure.persistence.MetricEventJpaDao;
import com.wikiagent.infrastructure.persistence.llm.ModelCallLogJpaDao;
import com.wikiagent.repo.KbChildChunkRepo;
import com.wikiagent.repo.KbDocumentRepo;
import com.wikiagent.repo.KbParentChunkRepo;
import com.wikiagent.repo.lineage.ExtractedFieldRepo;
import com.wikiagent.repo.rule.ReviewCaseRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线评测运行器（规格 §H AC-H2）：播种 → 四类黄金样本评测（真实组件 + 录制桩）
 * → 七项指标计算 → 写 {@code EvalReport.json}。
 * <p>
 * 默认<b>不装配</b>（{@code wikiagent.eval.enabled=true} 才启用），对既有部署零影响；
 * 离线运行零真实 API key、零 Docker/Milvus/Redis 依赖（检索强制本地降级、AI 全录制）。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.eval.enabled", havingValue = "true")
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    private static final List<MetricCalculator> CALCULATORS = List.of(
            new FieldAccuracyCalculator(),
            new RetrievalHitCalculator(),
            new CitationCorrectCalculator(),
            new JudgeErrorCalculator(),
            new HumanEditCalculator(),
            new ResponseTimeCalculator(),
            new CostPerTurnCalculator());

    private final ObjectMapper objectMapper;
    private final EvalProperties properties;
    private final EvalSeeder seeder;
    private final KbDocumentRepo docRepo;
    private final KbParentChunkRepo parentRepo;
    private final KbChildChunkRepo childRepo;
    private final KnowledgeMetadataJpaDao metadataDao;
    private final MetricEventJpaDao metricEventDao;
    private final KbFeedbackJpaDao feedbackDao;
    private final ExtractedFieldRepo fieldRepo;
    private final ReviewCaseRepo reviewCaseRepo;
    private final ModelCallLogJpaDao modelCallDao;

    public EvalRunner(ObjectMapper objectMapper,
                      EvalProperties properties,
                      EvalSeeder seeder,
                      KbDocumentRepo docRepo,
                      KbParentChunkRepo parentRepo,
                      KbChildChunkRepo childRepo,
                      KnowledgeMetadataJpaDao metadataDao,
                      MetricEventJpaDao metricEventDao,
                      KbFeedbackJpaDao feedbackDao,
                      ExtractedFieldRepo fieldRepo,
                      ReviewCaseRepo reviewCaseRepo,
                      ModelCallLogJpaDao modelCallDao) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.seeder = seeder;
        this.docRepo = docRepo;
        this.parentRepo = parentRepo;
        this.childRepo = childRepo;
        this.metadataDao = metadataDao;
        this.metricEventDao = metricEventDao;
        this.feedbackDao = feedbackDao;
        this.fieldRepo = fieldRepo;
        this.reviewCaseRepo = reviewCaseRepo;
        this.modelCallDao = modelCallDao;
    }

    public EvalReport runAll(EvalOptions options) {
        Instant runStart = Instant.now();
        EvalGoldenLoader loader = new EvalGoldenLoader(objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false));

        // 1) 幂等播种（eval- 前缀隔离）
        seeder.seed(loader.loadSeed());

        // 2) 四类黄金样本评测
        List<SampleResult> allResults = new ArrayList<>();
        List<CitationSample> citations = List.of();
        if (options.includes(com.wikiagent.domain.eval.EvalCategory.PARSE)) {
            SuiteOutput out = new ParseEvalSuite(loader).run();
            allResults.addAll(out.results());
        }
        if (options.includes(com.wikiagent.domain.eval.EvalCategory.RETRIEVE)) {
            SuiteOutput out = new RetrieveEvalSuite(loader, parentRepo, docRepo, childRepo,
                    metadataDao, metricEventDao).run();
            allResults.addAll(out.results());
            citations = out.citations();
        }
        if (options.includes(com.wikiagent.domain.eval.EvalCategory.RULE)) {
            allResults.addAll(new RuleEvalSuite(loader).run().results());
        }
        if (options.includes(com.wikiagent.domain.eval.EvalCategory.ANOMALY)) {
            allResults.addAll(new AnomalyEvalSuite(loader).run().results());
        }

        // 3) 指标计算（窗口=本次运行起；judge 录制评判打当前时间戳）
        List<JudgeVerdict> verdicts = loader.loadJudgeVerdicts(Instant.now());
        JpaEvalDataSource dataSource = new JpaEvalDataSource(
                fieldRepo, metricEventDao, feedbackDao, reviewCaseRepo, modelCallDao,
                childRepo, citations, verdicts);
        EvalWindow window = EvalWindow.since(runStart.minusSeconds(2));
        Map<String, MetricValue> summary = new LinkedHashMap<>();
        for (MetricCalculator calc : CALCULATORS) {
            summary.put(calc.key(), calc.compute(dataSource, window));
        }

        // 4) 装配报告 + 落盘
        String runId = options.runId() != null ? options.runId() : generateRunId(runStart);
        EvalReport report = new EvalReport(
                runStart.toString(),
                runId,
                gitInfo(),
                options.live() ? "live" : "offline",
                summary,
                allResults,
                baselinePlaceholder());

        String outputDir = options.outputDir() != null && !options.outputDir().isBlank()
                ? options.outputDir() : properties.getOutputDir();
        EvalReportStore store = new EvalReportStore(outputDir, objectMapper);
        store.write(report);
        log.info("离线评测完成 runId={} 样本={} 报告目录={}",
                runId, allResults.size(), outputDir);
        return report;
    }

    private static String generateRunId(Instant at) {
        return "eval-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneId.systemDefault()).format(at);
    }

    /** CI 环境变量注入 Git 信息；本地离线全 null（如实呈现）。 */
    private static EvalReport.GitInfo gitInfo() {
        String commit = System.getenv("GITHUB_SHA");
        String ref = System.getenv("GITHUB_REF_NAME");
        String workflowRun = System.getenv("GITHUB_RUN_ID");
        if (commit == null && ref == null && workflowRun == null) {
            return null;
        }
        return new EvalReport.GitInfo(commit, ref, workflowRun);
    }

    /** 基线占位：待首次 CI 夜间运行填充，禁止编造数值。 */
    private static Map<String, Object> baselinePlaceholder() {
        Map<String, Object> placeholder = new LinkedHashMap<>();
        placeholder.put("status", "PENDING_FIRST_CI_NIGHTLY_RUN");
        placeholder.put("note", "基线数值待首次 CI 夜间运行（eval.yml schedule）后填充，禁止编造");
        return placeholder;
    }
}
