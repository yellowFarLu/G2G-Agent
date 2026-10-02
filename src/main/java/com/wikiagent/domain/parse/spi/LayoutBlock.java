package com.wikiagent.domain.parse.spi;

/**
 * 版面块：阅读顺序 order（从小到大）+ 类型 + 可选坐标 + 块内文本。
 */
public record LayoutBlock(int order, LayoutBlockType type, BBox bbox, String text) {

    public LayoutBlock(int order, LayoutBlockType type, String text) {
        this(order, type, null, text);
    }
}
