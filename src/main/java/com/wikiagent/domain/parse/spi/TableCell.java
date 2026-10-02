package com.wikiagent.domain.parse.spi;

/**
 * 表格单元格：0 基行列坐标 + 跨跨行/跨列（默认 1）+ 文本 + 可选坐标。
 */
public record TableCell(int row, int col, int rowSpan, int colSpan, String text, BBox bbox) {

    public TableCell(int row, int col, String text) {
        this(row, col, 1, 1, text, null);
    }
}
