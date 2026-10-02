package com.wikiagent.domain.prompt;

/**
 * 模板渲染结果：渲染后内容 + 实际使用的模板版本。
 *
 * @param version 模板版本号；-1 表示未命中模板（调用方兜底逻辑产物）
 */
public record RenderedPrompt(String content, int version) {

    /** 未走模板渲染的兜底结果。 */
    public static RenderedPrompt fallback(String content) {
        return new RenderedPrompt(content, -1);
    }
}
