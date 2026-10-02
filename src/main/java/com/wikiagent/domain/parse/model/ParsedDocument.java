package com.wikiagent.domain.parse.model;

import com.wikiagent.domain.parse.spi.TranscriptResult;

import java.util.List;

/**
 * 富解析结果：介质类型 + 全文 + 分页（页文本/OCR 坐标置信度/版面块/冲突标记）
 * + 跨页表格 + 录音时间轴。
 * <p>
 * {@code aiSkipped}=true 表示该介质必须依赖 AI（图片 OCR/录音 ASR/扫描页 OCR）
 * 但当前供应商未配置（none/无 Key），流水线终态 AI_SKIPPED，不进入切分。
 * 供应商已配置但调用失败（超时/熔断）走任务重试，不使用本标记。
 */
public record ParsedDocument(
        DocKind kind,
        String fullText,
        List<ParsedPage> pages,
        List<StitchedTable> tables,
        TranscriptResult transcript,
        boolean aiSkipped) {

    public ParsedDocument {
        pages = pages == null ? List.of() : List.copyOf(pages);
        tables = tables == null ? List.of() : List.copyOf(tables);
        fullText = fullText == null ? "" : fullText;
    }

    public static ParsedDocument of(DocKind kind, String fullText, List<ParsedPage> pages) {
        return new ParsedDocument(kind, fullText, pages, List.of(), null, false);
    }

    public static ParsedDocument skipped(DocKind kind) {
        return new ParsedDocument(kind, "", List.of(), List.of(), null, true);
    }

    public ParsedDocument withTables(List<StitchedTable> stitchedTables) {
        return new ParsedDocument(kind, fullText, pages,
                stitchedTables == null ? List.of() : List.copyOf(stitchedTables), transcript, aiSkipped);
    }

    public boolean hasTimeline() {
        return transcript != null && !transcript.segments().isEmpty();
    }

    public boolean hasConflict() {
        return pages.stream().anyMatch(ParsedPage::conflict)
                || tables.stream().anyMatch(StitchedTable::ambiguous);
    }
}
