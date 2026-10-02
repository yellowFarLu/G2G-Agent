package com.wikiagent.domain.parse.spi;

/**
 * 页面元素包围盒（左上角原点，单位与供应商返回一致，默认像素）。
 */
public record BBox(float x, float y, float width, float height) {

    public static BBox of(float x, float y, float width, float height) {
        return new BBox(x, y, width, height);
    }
}
