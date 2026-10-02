package com.wikiagent.application.parse;

import com.wikiagent.domain.parse.model.StitchedCell;
import com.wikiagent.domain.parse.model.StitchedTable;
import com.wikiagent.domain.parse.spi.TableCell;
import com.wikiagent.domain.parse.spi.TableResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 跨页表格拼接（B3）：把连续页上的同一张表的 {@link TableResult} 拼成单一矩阵，
 * 行坐标全局连续，单元格保留来源页。歧义场景（列数不一致/续页重复表头）
 * 置 {@code ambiguous=true} 并写 notes，上层打 CONFLICT 标记 + STITCHED edge detail。
 */
@Component
public class TableStitcher {

    public StitchedTable stitch(List<TableResult> pageTables) {
        if (pageTables == null || pageTables.isEmpty()) {
            throw new IllegalArgumentException("无表格可拼接");
        }
        List<String> notes = new ArrayList<>();
        Set<Integer> pageNos = new LinkedHashSet<>();
        int firstCols = pageTables.get(0).cols();

        // 列数不一致 → 歧义拼接（保留各页原列结构，统一矩阵宽度，标 CONFLICT）
        boolean colMismatch = pageTables.stream().anyMatch(t -> t.cols() != firstCols);
        int globalCols = colMismatch
                ? pageTables.stream().mapToInt(TableResult::cols).max().orElse(firstCols)
                : firstCols;
        if (colMismatch) {
            notes.add("跨页表格列数不一致，按最大列数拼接，需人工确认");
        }

        List<StitchedCell> cells = new ArrayList<>();
        int rowOffset = 0;
        boolean repeatedHeader = false;
        List<String> firstPageHeader = headerRow(pageTables.get(0));

        for (int p = 0; p < pageTables.size(); p++) {
            TableResult table = pageTables.get(p);
            pageNos.add(table.pageNo());
            List<TableCell> pageCells = table.orderedCells();

            boolean dropHeader = p > 0 && !firstPageHeader.isEmpty()
                    && firstPageHeader.equals(headerRow(table));
            if (dropHeader) {
                repeatedHeader = true;
                notes.add("第 " + table.pageNo() + " 页疑似重复表头，已去重一行（保留歧义标记）");
            }

            for (TableCell c : pageCells) {
                if (dropHeader && c.row() == 0) {
                    continue;
                }
                int globalRow = c.row() + rowOffset - (dropHeader ? 1 : 0);
                cells.add(new StitchedCell(globalRow, c.col(), c.rowSpan(), c.colSpan(),
                        c.text(), table.pageNo(), c.bbox()));
            }
            // 下一页行偏移按本页逻辑行数（去表头减 1）
            rowOffset += Math.max(0, table.rows() - (dropHeader ? 1 : 0));
        }
        // 总行数以最大全局行坐标 +1 为准
        int finalRows = cells.stream().mapToInt(StitchedCell::row).max().orElse(-1) + 1;

        return new StitchedTable(finalRows, globalCols, cells, List.copyOf(pageNos),
                colMismatch || repeatedHeader, notes);
    }

    private List<String> headerRow(TableResult table) {
        List<String> header = new ArrayList<>();
        List<TableCell> row0 = table.orderedCells().stream()
                .filter(c -> c.row() == 0)
                .sorted(java.util.Comparator.comparingInt(TableCell::col))
                .toList();
        for (TableCell c : row0) {
            header.add(c.text() == null ? "" : c.text().strip());
        }
        return header;
    }
}
