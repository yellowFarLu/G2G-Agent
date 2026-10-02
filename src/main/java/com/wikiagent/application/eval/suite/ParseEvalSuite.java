package com.wikiagent.application.eval.suite;

import com.wikiagent.application.eval.EvalGoldenLoader;
import com.wikiagent.application.eval.support.EvalFixtures;
import com.wikiagent.application.eval.support.OfflineEvalAiProvider;
import com.wikiagent.application.parse.DocumentAiGateway;
import com.wikiagent.application.parse.PageConflictDetector;
import com.wikiagent.application.parse.PageStructureService;
import com.wikiagent.application.parse.ParseInputValidator;
import com.wikiagent.application.parse.PdfPageRenderer;
import com.wikiagent.application.parse.ProviderExecutor;
import com.wikiagent.application.parse.ProviderRegistry;
import com.wikiagent.application.parse.RichDocumentParser;
import com.wikiagent.application.parse.TableStitcher;
import com.wikiagent.config.ParseProperties;
import com.wikiagent.domain.eval.EvalCategory;
import com.wikiagent.domain.eval.SampleResult;
import com.wikiagent.domain.eval.golden.ParseGoldenCase;
import com.wikiagent.domain.parse.model.ParsedDocument;
import com.wikiagent.domain.parse.model.StitchedTable;
import com.wikiagent.infrastructure.parse.TikaTextExtractor;
import com.wikiagent.service.ingest.DocumentParser;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PARSE 评测套件（AC-H1/H2）：真实解析组件链（与 RichDocumentParserTest 同构装配）
 * + {@link OfflineEvalAiProvider} 录制桩 + 合成/既有夹具，验证：
 * 文本 PDF、扫描 OCR、加密口令续跑、跨页表格拼接、图片 OCR、音频 ASR、旧版 .doc。
 * 零真实 API key、零网络。
 */
public class ParseEvalSuite {

    /** 跨页表格样本额外断言的录制单元格内容（与 OfflineEvalAiProvider 录制一致）。 */
    private static final List<String> STITCHED_CELL_TOKENS =
            List.of("商品编码", "工业轴承", "密封胶条");
    private static final String CROSS_PAGE_KIND = "pdf-cross-page-table";

    private final EvalGoldenLoader loader;
    private final RichDocumentParser parser;
    private final PageStructureService structureService;

    public ParseEvalSuite(EvalGoldenLoader loader) {
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
        this.structureService = new PageStructureService(gateway, new PdfPageRenderer(),
                new TableStitcher());
    }

    public SuiteOutput run() {
        List<SampleResult> results = new ArrayList<>();
        for (ParseGoldenCase c : loader.loadParseGolden()) {
            results.add(runOne(c));
        }
        return SuiteOutput.of(results);
    }

    private SampleResult runOne(ParseGoldenCase c) {
        if (c.skipReason() != null && !c.skipReason().isBlank()) {
            return SampleResult.skipped(c.id(), EvalCategory.PARSE, c.skipReason());
        }
        List<String> failures = new ArrayList<>();
        Map<String, Object> metrics = new LinkedHashMap<>();
        int totalChecks = 0;
        int passedChecks = 0;
        try {
            byte[] bytes = fixtureBytes(c.fixturePath());
            String filename = filename(c);
            ParsedDocument parsed = parser.parse(c.id(), filename, bytes, c.password());
            metrics.put("kind", parsed.kind().name());
            metrics.put("aiSkipped", parsed.aiSkipped());
            metrics.put("pageCountParsed", parsed.pages().size());

            // 跨页表格：先走真实 LAYOUT/TABLE 识别 + Stitcher 拼接
            StitchedTable table = null;
            if (c.expects().tableRows() != null) {
                ParsedDocument analyzed = structureService.analyze(c.id(), parsed, bytes, c.password());
                if (analyzed.tables().isEmpty()) {
                    failures.add("期望跨页表格但未拼接出任何 StitchedTable");
                } else {
                    table = analyzed.tables().get(0);
                    metrics.put("tableRows", table.rows());
                    metrics.put("tablePageNos", table.pageNos());
                    metrics.put("tableAmbiguous", table.ambiguous());
                    totalChecks++;
                    if (table.rows() == c.expects().tableRows()) {
                        passedChecks++;
                    } else {
                        failures.add("tableRows 期望=" + c.expects().tableRows()
                                + " 实际=" + table.rows());
                    }
                    if (CROSS_PAGE_KIND.equals(c.kind())) {
                        String flattened = table.cells().stream()
                                .map(cell -> cell.text() == null ? "" : cell.text())
                                .reduce("", String::concat);
                        totalChecks++;
                        if (table.ambiguous()) {
                            passedChecks++;
                        } else {
                            failures.add("跨页重复表头应标记 ambiguous=true");
                        }
                        for (String token : STITCHED_CELL_TOKENS) {
                            totalChecks++;
                            if (flattened.contains(token)) {
                                passedChecks++;
                            } else {
                                failures.add("拼接单元格缺少内容: " + token);
                            }
                        }
                    }
                }
            }

            if (c.expects().textContains() != null) {
                for (String token : c.expects().textContains()) {
                    totalChecks++;
                    if (parsed.fullText().contains(token)) {
                        passedChecks++;
                    } else {
                        failures.add("全文缺少: " + token);
                    }
                }
            }
            if (c.expects().pageCount() != null) {
                totalChecks++;
                if (parsed.pages().size() == c.expects().pageCount()) {
                    passedChecks++;
                } else {
                    failures.add("pageCount 期望=" + c.expects().pageCount()
                            + " 实际=" + parsed.pages().size());
                }
            }
            if (c.expects().fields() != null) {
                for (Map.Entry<String, String> e : c.expects().fields().entrySet()) {
                    totalChecks++;
                    if (parsed.fullText().contains(e.getValue())) {
                        passedChecks++;
                    } else {
                        failures.add("字段 " + e.getKey() + " 值未出现在全文: " + e.getValue());
                    }
                }
            }
            metrics.put("checksPassed", passedChecks);
            metrics.put("checksTotal", totalChecks);
            double score = totalChecks == 0 ? 0.0 : (double) passedChecks / totalChecks;
            if (failures.isEmpty()) {
                return SampleResult.passed(c.id(), EvalCategory.PARSE, score, metrics);
            }
            return SampleResult.failed(c.id(), EvalCategory.PARSE, score, metrics,
                    String.join("；", failures));
        } catch (Exception e) {
            return SampleResult.error(c.id(), EvalCategory.PARSE,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private byte[] fixtureBytes(String fixturePath) throws Exception {
        if (fixturePath.startsWith("synthetic://")) {
            return EvalFixtures.synthetic(fixturePath);
        }
        if (fixturePath.startsWith("classpath:")) {
            return new ClassPathResource(fixturePath.substring("classpath:".length()))
                    .getContentAsByteArray();
        }
        throw new IllegalArgumentException("不支持的夹具协议: " + fixturePath);
    }

    private String filename(ParseGoldenCase c) {
        String path = c.fixturePath();
        if (path.startsWith("classpath:")) {
            return path.substring(path.lastIndexOf('/') + 1);
        }
        // synthetic://<kind tail> → 按 kind 给扩展名
        return switch (c.kind()) {
            case String k when k.startsWith("pdf") -> "synthetic.pdf";
            case String k when k.startsWith("image") -> "synthetic.png";
            case String k when k.startsWith("audio") -> "synthetic.mp3";
            default -> "synthetic.bin";
        };
    }
}
