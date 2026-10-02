package com.wikiagent.domain.parse.model;

import java.util.List;

/**
 * 跨页拼接后的单一逻辑表格。
 * <p>
 * 行坐标全局连续，单元格带 pageNo 溯源；{@code ambiguous}=true 表示拼接存在歧义
 * （列数不一致/续页疑似重复表头），上层须给 TABLE 产物打 CONFLICT 并写 edge detail，
 * 不能静默当作普通拼接。{@code notes} 记录具体歧义/处置说明。
 */
public record StitchedTable(
        int rows,
        int cols,
        List<StitchedCell> cells,
        List<Integer> pageNos,
        boolean ambiguous,
        List<String> notes) {

    public StitchedTable {
        cells = cells == null ? List.of() : List.copyOf(cells);
        pageNos = pageNos == null ? List.of() : List.copyOf(pageNos);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** 某页在全局表中起始行（用于 STITCHED edge detail 与展示分页线）。 */
    public int firstRowOfPage(int pageNo) {
        return cells.stream()
                .filter(c -> c.pageNo() == pageNo)
                .mapToInt(StitchedCell::row)
                .min().orElse(-1);
    }

    public String cellText(int row, int col) {
        return cells.stream()
                .filter(c -> c.row() == row && c.col() == col)
                .map(StitchedCell::text)
                .findFirst().orElse(null);
    }
}
