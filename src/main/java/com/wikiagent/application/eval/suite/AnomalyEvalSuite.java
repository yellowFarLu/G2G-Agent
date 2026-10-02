package com.wikiagent.application.eval.suite;

import com.wikiagent.application.extract.ExtractionSchemaRegistry;
import com.wikiagent.application.extract.FieldExtractionService;
import com.wikiagent.application.extract.FieldValidator;
import com.wikiagent.application.eval.EvalGoldenLoader;
import com.wikiagent.application.eval.support.EvalFixtures;
import com.wikiagent.application.eval.support.OfflineEvalAiProvider;
import com.wikiagent.application.eval.support.ScriptedExtractionClient;
import com.wikiagent.application.parse.DocumentAiGateway;
import com.wikiagent.application.parse.PageConflictDetector;
import com.wikiagent.application.parse.ParseInputValidator;
import com.wikiagent.application.parse.ProviderExecutor;
import com.wikiagent.application.parse.ProviderRegistry;
import com.wikiagent.application.parse.RichDocumentParser;
import com.wikiagent.config.ExtractProperties;
import com.wikiagent.config.ParseProperties;
import com.wikiagent.domain.eval.EvalCategory;
import com.wikiagent.domain.eval.SampleResult;
import com.wikiagent.domain.eval.anomaly.AnomalyClassifier;
import com.wikiagent.domain.eval.anomaly.AnomalyOutcome;
import com.wikiagent.domain.eval.golden.AnomalyGoldenCase;
import com.wikiagent.domain.extract.ExtractionReport;
import com.wikiagent.domain.parse.EncryptedDocumentException;
import com.wikiagent.domain.parse.model.DocKind;
import com.wikiagent.domain.parse.model.ParsedDocument;
import com.wikiagent.domain.parse.model.ParsedPage;
import com.wikiagent.infrastructure.parse.TikaTextExtractor;
import com.wikiagent.service.ingest.DocumentParser;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ANOMALY 评测套件（AC-H1/H2）：异常信号由<b>真实组件</b>产生，
 * 再经纯 Java {@link AnomalyClassifier}（与生产 catch 契约同一编码处）映射为处置结果，
 * 与 golden 逐字段比对——不在桩里直接写期望值。
 * <ul>
 *   <li>ENCRYPTED：RichDocumentParser 对合成加密 PDF 抛 EncryptedDocumentException（无/错口令）；</li>
 *   <li>CORRUPT：非 PDF 字节抛 IllegalStateException(PDF 解析失败)；</li>
 *   <li>OVERSIZE：maxFileMb=1 + 2MB 载荷，ParseInputValidator 抛 IllegalArgumentException；</li>
 *   <li>LOW_CONFIDENCE：真实 FieldExtractionService + 录制低置信固件 → needsReview=true；</li>
 *   <li>REVIEW_REJECT：分类器的复核驳回恢复映射（RUNNING）。</li>
 * </ul>
 */
public class AnomalyEvalSuite {

    private final EvalGoldenLoader loader;
    private final AnomalyClassifier classifier = new AnomalyClassifier();
    private final RichDocumentParser parser;

    public AnomalyEvalSuite(EvalGoldenLoader loader) {
        this.loader = loader;
        ParseProperties props = new ParseProperties();
        props.setProvider("eval-offline-stub");
        props.setRetryBackoffBaseMs(0);
        OfflineEvalAiProvider stub = new OfflineEvalAiProvider();
        ParseInputValidator validator = new ParseInputValidator(props);
        ProviderRegistry registry = new ProviderRegistry(List.of(stub), props);
        DocumentAiGateway gateway = new DocumentAiGateway(registry, new ProviderExecutor(props), props);
        TikaTextExtractor tika = new TikaTextExtractor();
        this.parser = new RichDocumentParser(validator, gateway, new DocumentParser(tika), tika,
                new PageConflictDetector(props));
    }

    public SuiteOutput run() {
        return loader.loadAnomalyGolden().stream()
                .map(this::runOne)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toList(), SuiteOutput::of));
    }

    private SampleResult runOne(AnomalyGoldenCase c) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        try {
            AnomalyOutcome actual = produce(c, metrics);
            AnomalyOutcome expected = new AnomalyOutcome(
                    c.expects().taskStatus(), c.expects().documentStatus(),
                    c.expects().humanKind(), c.expects().errorCode());
            List<String> diffs = classifier.mismatches(expected, actual);
            metrics.put("actual", actual);
            if (diffs.isEmpty()) {
                return SampleResult.passed(c.id(), EvalCategory.ANOMALY, 1.0, metrics);
            }
            return SampleResult.failed(c.id(), EvalCategory.ANOMALY, 0.0, metrics,
                    String.join("；", diffs));
        } catch (Exception e) {
            return SampleResult.error(c.id(), EvalCategory.ANOMALY,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private AnomalyOutcome produce(AnomalyGoldenCase c, Map<String, Object> metrics) {
        switch (c.scenario()) {
            case "ENCRYPTED" -> {
                byte[] pdf = EvalFixtures.synthetic(EvalFixtures.SYN_ENCRYPTED);
                boolean wrongPassword = c.id().contains("wrong-password");
                metrics.put("passwordProvided", wrongPassword ? "bad-password" : null);
                try {
                    parser.parse(c.id(), "encrypted.pdf", pdf,
                            wrongPassword ? "bad-password" : null);
                    throw new IllegalStateException("加密 PDF 应抛 EncryptedDocumentException，但解析成功");
                } catch (EncryptedDocumentException e) {
                    metrics.put("passwordRejected", e.passwordRejected());
                    return classifier.encrypted(e.passwordRejected());
                }
            }
            case "CORRUPT" -> {
                try {
                    parser.parse(c.id(), "broken.pdf",
                            "not-a-real-pdf-content".getBytes(StandardCharsets.UTF_8), null);
                    throw new IllegalStateException("损坏字节应抛 IllegalStateException，但解析成功");
                } catch (IllegalStateException e) {
                    if (e.getMessage() == null || !e.getMessage().contains("PDF 解析失败")) {
                        throw e;
                    }
                    metrics.put("signal", "IllegalStateException: " + e.getMessage());
                    return classifier.corrupt();
                }
            }
            case "OVERSIZE" -> {
                ParseProperties props = new ParseProperties();
                props.setMaxFileMb(1);
                ParseInputValidator validator = new ParseInputValidator(props);
                try {
                    validator.validate("oversize.pdf", EvalFixtures.oversizeBytes());
                    throw new IllegalStateException("超大文件应被校验拒绝，但校验通过");
                } catch (IllegalArgumentException e) {
                    metrics.put("signal", e.getMessage());
                    return classifier.validationRejected();
                }
            }
            case "LOW_CONFIDENCE" -> {
                ExtractionSchemaRegistry registry = new ExtractionSchemaRegistry();
                // load() 为包私有（正常由 @PostConstruct 触发）；评测不拥有该类，反射触发一次
                invokePostConstructLoad(registry);
                FieldExtractionService extractor = new FieldExtractionService(
                        registry, new FieldValidator(), new ScriptedExtractionClient(),
                        new ExtractProperties());
                ParsedDocument invoiceDoc = ParsedDocument.of(DocKind.PDF, "", List.of(
                        ParsedPage.text(1, "发票号码：12345678 销售方：北京云??公司（印章模糊）"),
                        ParsedPage.text(2, "价税合计 1280.50")));
                ExtractionReport report = extractor.extract(c.id(), "invoice-demo", invoiceDoc);
                metrics.put("needsReview", report.needsReview());
                metrics.put("reviewReasons", report.reviewReasons());
                if (!report.needsReview()) {
                    throw new IllegalStateException("录制低置信固件应产出 needsReview=true");
                }
                return classifier.lowConfidence();
            }
            case "REVIEW_REJECT" -> {
                metrics.put("disposition", "REJECT");
                return classifier.afterReviewDisposition("REJECT");
            }
            default -> throw new IllegalArgumentException("未知异常场景: " + c.scenario());
        }
    }

    /** 反射触发包私有的 @PostConstruct load()（classpath*:extract-schemas/*.json）。 */
    private static void invokePostConstructLoad(ExtractionSchemaRegistry registry) {
        try {
            java.lang.reflect.Method load = ExtractionSchemaRegistry.class.getDeclaredMethod("load");
            load.setAccessible(true);
            load.invoke(registry);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("加载抽取 schema 失败: " + e.getMessage(), e);
        }
    }
}
