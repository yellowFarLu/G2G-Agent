package com.wikiagent.domain.parse.spi;

import java.util.Comparator;
import java.util.List;

/**
 * 版面分析结果：按阅读顺序排列的块列表。
 */
public record LayoutResult(List<LayoutBlock> blocks) {

    public LayoutResult {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    /** 返回按 order 排序后的块（防御供应商乱序）。 */
    public List<LayoutBlock> ordered() {
        return blocks.stream().sorted(Comparator.comparingInt(LayoutBlock::order)).toList();
    }
}
