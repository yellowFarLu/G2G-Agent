package com.wikiagent.infrastructure.parse.dashscope;

import com.fasterxml.jackson.core.JsonFactoryBuilder;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikiagent.domain.parse.spi.BBox;
import com.wikiagent.domain.parse.spi.LayoutBlock;
import com.wikiagent.domain.parse.spi.LayoutBlockType;
import com.wikiagent.domain.parse.spi.LayoutResult;
import com.wikiagent.domain.parse.spi.OcrResult;
import com.wikiagent.domain.parse.spi.OcrSpan;
import com.wikiagent.domain.parse.spi.ParseProviderException;
import com.wikiagent.domain.parse.spi.TableCell;
import com.wikiagent.domain.parse.spi.TableResult;

import java.util.ArrayList;
import java.util.List;

/**
 * qwen-vl JSON 响应 → 领域结果的容错解析（字段缺失给默认值，结构非法抛非重试异常）。
 */
final class DashScopeVisionResultParser {

    /** 容忍 LLM 返回的未转义控制字符（字符串内裸换行等）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper(
            new JsonFactoryBuilder()
                    .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                    .build());

    private DashScopeVisionResultParser() {
    }

    static OcrResult parseOcr(String json, int pageNo) {
        JsonNode root = read(json);
        List<OcrSpan> spans = new ArrayList<>();
        for (JsonNode s : root.path("spans")) {
            String text = s.path("text").asText("");
            if (text.isEmpty()) {
                continue;
            }
            spans.add(new OcrSpan(text, parseBBox(s.path("bbox")),
                    clampConfidence(s.path("confidence").asDouble(Double.NaN))));
        }
        String fullText = root.path("fullText").asText(null);
        if (fullText == null || fullText.isBlank()) {
            fullText = String.join("\n", spans.stream().map(OcrSpan::text).toList());
        }
        double confidence = root.path("confidence").asDouble(Double.NaN);
        if (Double.isNaN(confidence)) {
            confidence = spans.isEmpty() ? 0.9
                    : spans.stream().mapToDouble(OcrSpan::confidence).average().orElse(0.9);
        }
        return new OcrResult(fullText, spans, clampConfidence(confidence));
    }

    static LayoutResult parseLayout(String json) {
        JsonNode root = read(json);
        List<LayoutBlock> blocks = new ArrayList<>();
        int autoOrder = 0;
        for (JsonNode b : root.path("blocks")) {
            String typeRaw = b.path("type").asText("TEXT");
            LayoutBlockType type;
            try {
                type = LayoutBlockType.valueOf(typeRaw.toUpperCase());
            } catch (IllegalArgumentException e) {
                type = LayoutBlockType.TEXT;
            }
            int order = b.path("order").asInt(autoOrder);
            blocks.add(new LayoutBlock(order, type, parseBBox(b.path("bbox")), b.path("text").asText("")));
            autoOrder++;
        }
        return new LayoutResult(blocks);
    }

    static TableResult parseTable(String json, int pageNo) {
        JsonNode root = read(json);
        int rows = root.path("rows").asInt(0);
        int cols = root.path("cols").asInt(0);
        List<TableCell> cells = new ArrayList<>();
        int maxRow = -1;
        int maxCol = -1;
        for (JsonNode c : root.path("cells")) {
            int row = c.path("row").asInt(-1);
            int col = c.path("col").asInt(-1);
            if (row < 0 || col < 0) {
                continue;
            }
            cells.add(new TableCell(row, col,
                    c.path("rowSpan").asInt(1), c.path("colSpan").asInt(1),
                    c.path("text").asText(""), parseBBox(c.path("bbox"))));
            maxRow = Math.max(maxRow, row);
            maxCol = Math.max(maxCol, col);
        }
        if (rows <= 0) {
            rows = maxRow + 1;
        }
        if (cols <= 0) {
            cols = maxCol + 1;
        }
        return new TableResult(pageNo, rows, cols, cells);
    }

    private static BBox parseBBox(JsonNode node) {
        if (!node.isArray() || node.size() < 4) {
            return null;
        }
        return BBox.of((float) node.get(0).asDouble(0), (float) node.get(1).asDouble(0),
                (float) node.get(2).asDouble(0), (float) node.get(3).asDouble(0));
    }

    private static double clampConfidence(double v) {
        if (Double.isNaN(v)) {
            return 0.9;
        }
        return Math.max(0, Math.min(1, v));
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new ParseProviderException("视觉模型返回非 JSON，无法解析: " + e.getMessage(), false, e);
        }
    }
}
