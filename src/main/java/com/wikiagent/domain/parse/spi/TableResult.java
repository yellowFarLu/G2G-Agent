package com.wikiagent.domain.parse.spi;

import java.util.Comparator;
import java.util.List;

/**
 * 单页表格识别结果：行列数 + 单元格集合（可能稀疏，跨页拼接由应用层完成）。
 */
public record TableResult(int pageNo, int rows, int cols, List<TableCell> cells) {

    public TableResult {
        cells = cells == null ? List.of() : List.copyOf(cells);
    }

    public List<TableCell> orderedCells() {
        return cells.stream()
                .sorted(Comparator.comparingInt(TableCell::row).thenComparingInt(TableCell::col))
                .toList();
    }

    /** 取某单元格文本，越界/缺失返回 null。 */
    public String cellText(int row, int col) {
        return cells.stream()
                .filter(c -> c.row() == row && c.col() == col)
                .map(TableCell::text)
                .findFirst().orElse(null);
    }
}
