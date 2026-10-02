package com.wikiagent.application.parse;

import com.wikiagent.config.ParseProperties;
import com.wikiagent.domain.parse.model.DocKind;
import com.wikiagent.domain.parse.model.ParsedDocument;
import com.wikiagent.domain.parse.model.ParsedPage;
import com.wikiagent.domain.parse.spi.AudioRequest;
import com.wikiagent.domain.parse.spi.Capability;
import com.wikiagent.domain.parse.spi.DocumentAiProvider;
import com.wikiagent.domain.parse.spi.LayoutBlock;
import com.wikiagent.domain.parse.spi.LayoutBlockType;
import com.wikiagent.domain.parse.spi.LayoutResult;
import com.wikiagent.domain.parse.spi.OcrRequest;
import com.wikiagent.domain.parse.spi.OcrResult;
import com.wikiagent.domain.parse.spi.TableRequest;
import com.wikiagent.domain.parse.spi.TableResult;
import com.wikiagent.domain.parse.spi.TranscriptResult;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B3 版面/表格结构分析测试：表格块页触发 TABLE 并跨页拼接；无表格块不调 TABLE；
 * LAYOUT 不可用时原样返回。栅格化走真实 PDFBox，AI 返回为内存桩。
 */
class PageStructureServiceTest {

    static class StructureStub implements DocumentAiProvider {
        boolean layoutCapable = true;
        boolean tableCapable = true;
        int layoutCalls;
        int tableCalls;

        @Override
        public String name() {
            return "stub";
        }

        @Override
        public Set<Capability> capabilities() {
            java.util.EnumSet<Capability> caps = java.util.EnumSet.noneOf(Capability.class);
            if (layoutCapable) {
                caps.add(Capability.LAYOUT);
            }
            if (tableCapable) {
                caps.add(Capability.TABLE);
            }
            return caps;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public OcrResult ocr(OcrRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TranscriptResult transcribe(AudioRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LayoutResult layout(com.wikiagent.domain.parse.spi.LayoutRequest request) {
            layoutCalls++;
            // 每页都报含一个表格块
            return new LayoutResult(List.of(
                    new LayoutBlock(0, LayoutBlockType.TEXT, "正文"),
                    new LayoutBlock(1, LayoutBlockType.TABLE, "费用表")));
        }

        @Override
        public TableResult table(TableRequest request) {
            tableCalls++;
            int pageNo = request.pageNo();
            if (pageNo == 1) {
                return new TableResult(1, 2, 2, List.of(
                        new com.wikiagent.domain.parse.spi.TableCell(0, 0, "项目"),
                        new com.wikiagent.domain.parse.spi.TableCell(0, 1, "金额"),
                        new com.wikiagent.domain.parse.spi.TableCell(1, 0, "房租"),
                        new com.wikiagent.domain.parse.spi.TableCell(1, 1, "100")));
            }
            return new TableResult(2, 1, 2, List.of(
                    new com.wikiagent.domain.parse.spi.TableCell(0, 0, "餐饮"),
                    new com.wikiagent.domain.parse.spi.TableCell(0, 1, "30")));
        }
    }

    private PageStructureService service(StructureStub stub) {
        ParseProperties props = new ParseProperties();
        props.setProvider("stub");
        ProviderRegistry registry = new ProviderRegistry(List.of(stub), props);
        DocumentAiGateway gateway = new DocumentAiGateway(registry, new ProviderExecutor(props), props);
        return new PageStructureService(gateway, new PdfPageRenderer(), new TableStitcher());
    }

    private byte[] blankPdf(int pages) {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ParsedDocument pdfDoc(int pages) {
        return ParsedDocument.of(DocKind.PDF, "x",
                java.util.stream.IntStream.rangeClosed(1, pages)
                        .mapToObj(p -> ParsedPage.text(p, "第" + p + "页文本"))
                        .toList());
    }

    @Test
    void layoutAnnotatesPagesAndConsecutiveTablePagesAreStitched() {
        StructureStub stub = new StructureStub();
        PageStructureService svc = service(stub);

        ParsedDocument out = svc.analyze("d1", pdfDoc(2), blankPdf(2), null);

        assertThat(stub.layoutCalls).isEqualTo(2);
        assertThat(stub.tableCalls).isEqualTo(2);
        assertThat(out.pages()).allSatisfy(p -> assertThat(p.layout()).isNotNull());
        assertThat(out.tables()).hasSize(1);
        var table = out.tables().get(0);
        assertThat(table.pageNos()).containsExactly(1, 2);
        assertThat(table.rows()).isEqualTo(3);
        assertThat(table.ambiguous()).isFalse();
        assertThat(table.cellText(2, 0)).isEqualTo("餐饮");
    }

    @Test
    void pagesWithoutTableBlocksDoNotCallTableApi() {
        StructureStub stub = new StructureStub() {
            @Override
            public LayoutResult layout(com.wikiagent.domain.parse.spi.LayoutRequest request) {
                layoutCalls++;
                return new LayoutResult(List.of(new LayoutBlock(0, LayoutBlockType.TEXT, "纯文本")));
            }
        };
        PageStructureService svc = service(stub);

        ParsedDocument out = svc.analyze("d2", pdfDoc(1), blankPdf(1), null);

        assertThat(stub.tableCalls).isZero();
        assertThat(out.tables()).isEmpty();
        assertThat(out.pages().get(0).layout().blocks()).hasSize(1);
    }

    @Test
    void missingLayoutCapabilityReturnsDocumentUnchanged() {
        StructureStub stub = new StructureStub();
        stub.layoutCapable = false;
        stub.tableCapable = false;
        PageStructureService svc = service(stub);

        ParsedDocument doc = pdfDoc(1);
        ParsedDocument out = svc.analyze("d3", doc, blankPdf(1), null);

        assertThat(out).isEqualTo(doc);
        assertThat(stub.layoutCalls).isZero();
        assertThat(stub.tableCalls).isZero();
    }
}
