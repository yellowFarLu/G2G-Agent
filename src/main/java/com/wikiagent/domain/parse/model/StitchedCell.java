package com.wikiagent.domain.parse.model;

import com.wikiagent.domain.parse.spi.BBox;

/**
 * 拼接后表格中的单元格：全局行列坐标 + 来源页（跨页溯源）+ 文本/坐标。
 */
public record StitchedCell(int row, int col, int rowSpan, int colSpan,
                           String text, int pageNo, BBox bbox) {
}
