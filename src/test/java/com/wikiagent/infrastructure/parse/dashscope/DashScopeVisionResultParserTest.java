package com.wikiagent.infrastructure.parse.dashscope;

import com.wikiagent.domain.parse.spi.LayoutBlockType;
import com.wikiagent.domain.parse.spi.ParseProviderException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * B-1 qwen-vl JSON 容错解析测试（全部为桩 JSON，不联网）。
 */
class DashScopeVisionResultParserTest {

    @Test
    void parsesOcrWithSpansBboxAndConfidence() {
        var result = DashScopeVisionResultParser.parseOcr("""
                {"fullText":"发票\n金额100",
                 "spans":[
                   {"text":"发票","confidence":0.98,"bbox":[10,20,100,30]},
                   {"text":"金额100","confidence":0.6,"bbox":[10,60,120,24]}
                 ],"confidence":0.8}
                """, 1);

        assertThat(result.fullText()).isEqualTo("发票\n金额100");
        assertThat(result.spans()).hasSize(2);
        assertThat(result.confidence()).isEqualTo(0.8);
        assertThat(result.spans().get(0).bbox()).isNotNull();
        assertThat(result.spans().get(0).bbox().x()).isEqualTo(10f);
    }

    @Test
    void ocrMissingFullTextJoinsSpanTextsAndMissingOverallConfidenceAverages() {
        var result = DashScopeVisionResultParser.parseOcr("""
                {"spans":[{"text":"甲","confidence":0.8},{"text":"乙","confidence":0.6}]}
                """, 2);

        assertThat(result.fullText()).isEqualTo("甲\n乙");
        assertThat(result.confidence()).isEqualTo(0.7);
    }

    @Test
    void parsesLayoutBlocksAndUnknownTypeFallsBackToText() {
        var result = DashScopeVisionResultParser.parseLayout("""
                {"blocks":[
                  {"order":2,"type":"weird","text":"正文","bbox":[1,2,3,4]},
                  {"order":1,"type":"TITLE","text":"标题"}
                ]}
                """);

        assertThat(result.blocks()).hasSize(2);
        assertThat(result.ordered().get(0).type()).isEqualTo(LayoutBlockType.TITLE);
        assertThat(result.ordered().get(1).type()).isEqualTo(LayoutBlockType.TEXT);
        assertThat(result.ordered().get(1).bbox().width()).isEqualTo(3f);
    }

    @Test
    void parsesTableCellsWithSpansAndInfersDimensions() {
        var result = DashScopeVisionResultParser.parseTable("""
                {"cells":[
                  {"row":0,"col":0,"rowSpan":1,"colSpan":2,"text":"抬头"},
                  {"row":1,"col":0,"text":"a"},
                  {"row":1,"col":1,"text":"b"}
                ]}
                """, 3);

        assertThat(result.pageNo()).isEqualTo(3);
        assertThat(result.rows()).isEqualTo(2);
        assertThat(result.cols()).isEqualTo(2);
        assertThat(result.cellText(0, 0)).isEqualTo("抬头");
        assertThat(result.orderedCells().get(0).colSpan()).isEqualTo(2);
    }

    @Test
    void invalidJsonIsNonRetryable() {
        Throwable t = catchThrowable(() -> DashScopeVisionResultParser.parseOcr("not-json", 1));
        assertThat(t).isInstanceOf(ParseProviderException.class);
        assertThat(((ParseProviderException) t).retryable()).isFalse();
    }

    @Test
    void stripsMarkdownCodeFence() {
        assertThat(DashScopeVisionClient.stripCodeFence("```json\n{\"a\":1}\n```"))
                .isEqualTo("{\"a\":1}");
        assertThat(DashScopeVisionClient.stripCodeFence(" {\"a\":1} "))
                .isEqualTo("{\"a\":1}");
    }
}
