package com.wikiagent.application.parse;

import com.wikiagent.domain.parse.model.DocKind;
import com.wikiagent.domain.parse.spi.Capability;
import com.wikiagent.domain.parse.spi.LayoutBlockType;
import com.wikiagent.domain.parse.spi.LayoutRequest;
import com.wikiagent.domain.parse.model.ParsedDocument;
import com.wikiagent.domain.parse.model.ParsedPage;
import com.wikiagent.domain.parse.model.StitchedTable;
import com.wikiagent.domain.parse.spi.LayoutResult;
import com.wikiagent.domain.parse.spi.TableRequest;
import com.wikiagent.domain.parse.spi.TableResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * B3 版面/表格结构分析（PDF 逐页）：
 * LAYOUT 回填版面块；检测到表格块的页调 TABLE 取矩阵，
 * 连续表格页经 {@link TableStitcher} 跨页拼接。能力缺失时跳过对应增强，
 * 不影响文本主流程；单页/多页表格都产出带 pageNo 溯源的 StitchedTable。
 */
@Component
public class PageStructureService {

    private static final Logger log = LoggerFactory.getLogger(PageStructureService.class);

    private final DocumentAiGateway gateway;
    private final PdfPageRenderer renderer;
    private final TableStitcher stitcher;

    public PageStructureService(DocumentAiGateway gateway, PdfPageRenderer renderer, TableStitcher stitcher) {
        this.gateway = gateway;
        this.renderer = renderer;
        this.stitcher = stitcher;
    }

    public ParsedDocument analyze(String docId, ParsedDocument parsed, byte[] pdfBytes, String password) {
        if (parsed.kind() != DocKind.PDF || parsed.aiSkipped()) {
            return parsed;
        }
        List<ParsedPage> pages = new ArrayList<>(parsed.pages());
        boolean layoutAvailable = gateway.has(Capability.LAYOUT);
        boolean tableAvailable = gateway.has(Capability.TABLE);

        // 1) 逐页版面分析
        if (layoutAvailable) {
            for (int i = 0; i < pages.size(); i++) {
                ParsedPage page = pages.get(i);
                try {
                    byte[] png = renderer.renderPng(pdfBytes, i, PdfPageRenderer.DEFAULT_SCALE, password);
                    Optional<LayoutResult> layout = gateway.layoutOptional(
                            new LayoutRequest(docId, page.pageNo(), png, "image/png"));
                    if (layout.isPresent()) {
                        pages.set(i, page.withLayout(layout.get()));
                    }
                } catch (Exception e) {
                    log.warn("版面分析失败 docId={} page={}: {}", docId, page.pageNo(), e.getMessage());
                }
            }
        }

        // 2) 含表格块的连续页 → TABLE 识别 + 跨页拼接
        List<StitchedTable> tables = new ArrayList<>(parsed.tables());
        if (tableAvailable) {
            tables.addAll(extractTableRuns(docId, pages, pdfBytes, password, layoutAvailable));
        }
        return new ParsedDocument(parsed.kind(), parsed.fullText(), pages, tables,
                parsed.transcript(), parsed.aiSkipped());
    }

    /**
     * 找出连续含表页的区间，逐页识别后拼接。版面能力缺失时无块信号，
     * 保守不跑表格（避免把纯文本页误识别为空表）。
     */
    private List<StitchedTable> extractTableRuns(String docId, List<ParsedPage> pages,
                                                 byte[] pdfBytes, String password,
                                                 boolean layoutAvailable) {
        if (!layoutAvailable) {
            return List.of();
        }
        List<StitchedTable> result = new ArrayList<>();
        int i = 0;
        while (i < pages.size()) {
            if (!hasTableBlock(pages.get(i))) {
                i++;
                continue;
            }
            int runStart = i;
            List<TableResult> runTables = new ArrayList<>();
            while (i < pages.size() && hasTableBlock(pages.get(i))) {
                ParsedPage page = pages.get(i);
                try {
                    byte[] png = renderer.renderPng(pdfBytes, i, PdfPageRenderer.DEFAULT_SCALE, password);
                    gateway.tableOptional(new TableRequest(docId, page.pageNo(), png, "image/png"))
                            .ifPresent(runTables::add);
                } catch (Exception e) {
                    log.warn("表格识别失败 docId={} page={}: {}", docId, page.pageNo(), e.getMessage());
                }
                i++;
            }
            if (runTables.size() == 1) {
                result.add(stitcher.stitch(runTables));
            } else if (runTables.size() > 1) {
                StitchedTable stitched = stitcher.stitch(runTables);
                log.info("跨页表格拼接 docId={} pages={} rows={} ambiguous={}",
                        docId, stitched.pageNos(), stitched.rows(), stitched.ambiguous());
                result.add(stitched);
            }
            if (i == runStart) {
                i++; // 防御性前进
            }
        }
        return result;
    }

    private boolean hasTableBlock(ParsedPage page) {
        return page.layout() != null && page.layout().blocks().stream()
                .anyMatch(b -> b.type() == LayoutBlockType.TABLE);
    }
}
