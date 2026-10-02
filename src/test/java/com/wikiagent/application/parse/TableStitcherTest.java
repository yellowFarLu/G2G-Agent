package com.wikiagent.application.parse;

import com.wikiagent.domain.parse.model.StitchedTable;
import com.wikiagent.domain.parse.spi.BBox;
import com.wikiagent.domain.parse.spi.TableCell;
import com.wikiagent.domain.parse.spi.TableResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B3 跨页表格拼接：行全局连续、单元格带来源页；重复表头去重并标歧义；列数不一致标歧义。
 */
class TableStitcherTest {

    private TableCell cell(int row, int col, String text, int pageNo) {
        return new TableCell(row, col, 1, 1, text,
                BBox.of(1, 2 + row, 30, 10));
    }

    private TableResult page(int pageNo, int rows, int cols, List<TableCell> cells) {
        return new TableResult(pageNo, rows, cols, cells);
    }

    @Test
    void stitchesTwoPagesWithContinuousRowsAndPageProvenance() {
        TableResult p1 = page(1, 3, 2, List.of(
                cell(0, 0, "项目", 1), cell(0, 1, "金额", 1),
                cell(1, 0, "房租", 1), cell(1, 1, "100", 1),
                cell(2, 0, "水电", 1), cell(2, 1, "20", 1)));
        TableResult p2 = page(2, 2, 2, List.of(
                cell(0, 0, "餐饮", 2), cell(0, 1, "30", 2),
                cell(1, 0, "交通", 2), cell(1, 1, "5", 2)));

        StitchedTable t = new TableStitcher().stitch(List.of(p1, p2));

        assertThat(t.ambiguous()).isFalse();
        assertThat(t.rows()).isEqualTo(5);
        assertThat(t.cols()).isEqualTo(2);
        assertThat(t.pageNos()).containsExactly(1, 2);
        assertThat(t.cellText(0, 0)).isEqualTo("项目");
        assertThat(t.cellText(3, 0)).isEqualTo("餐饮");
        assertThat(t.cellText(4, 1)).isEqualTo("5");
        // 页边界：第 2 页内容从全局第 3 行开始
        assertThat(t.firstRowOfPage(2)).isEqualTo(3);
        assertThat(t.cells().get(0).pageNo()).isEqualTo(1);
        assertThat(t.cells().stream().filter(c -> c.text().equals("交通")).findFirst()
                .orElseThrow().pageNo()).isEqualTo(2);
    }

    @Test
    void repeatedHeaderOnContinuationIsDroppedAndFlaggedAmbiguous() {
        TableResult p1 = page(1, 2, 2, List.of(
                cell(0, 0, "项目", 1), cell(0, 1, "金额", 1),
                cell(1, 0, "房租", 1), cell(1, 1, "100", 1)));
        TableResult p2 = page(2, 2, 2, List.of(
                cell(0, 0, "项目", 2), cell(0, 1, "金额", 2), // 重复表头
                cell(1, 0, "餐饮", 2), cell(1, 1, "30", 2)));

        StitchedTable t = new TableStitcher().stitch(List.of(p1, p2));

        assertThat(t.ambiguous()).isTrue();
        assertThat(t.notes()).anyMatch(n -> n.contains("重复表头"));
        // 仅保留首页表头一次；餐饮接在第 2 行
        assertThat(t.cellText(0, 0)).isEqualTo("项目");
        assertThat(t.cellText(2, 0)).isEqualTo("餐饮");
        assertThat(t.rows()).isEqualTo(3);
    }

    @Test
    void columnCountMismatchIsAmbiguousSingleTable() {
        TableResult p1 = page(1, 2, 3, List.of(
                new TableCell(0, 0, "a"), new TableCell(0, 1, "b"), new TableCell(0, 2, "c"),
                new TableCell(1, 0, "1"), new TableCell(1, 1, "2"), new TableCell(1, 2, "3")));
        TableResult p2 = page(2, 1, 2, List.of(
                new TableCell(0, 0, "x"), new TableCell(0, 1, "y")));

        StitchedTable t = new TableStitcher().stitch(List.of(p1, p2));

        assertThat(t.ambiguous()).isTrue();
        assertThat(t.cols()).isEqualTo(3);
        assertThat(t.notes()).anyMatch(n -> n.contains("列数不一致"));
        // 仍是单一表产物，第二页内容保留并可溯源
        assertThat(t.cellText(2, 0)).isEqualTo("x");
        assertThat(t.cells().stream().filter(c -> c.text().equals("x")).findFirst()
                .orElseThrow().pageNo()).isEqualTo(2);
    }
}
