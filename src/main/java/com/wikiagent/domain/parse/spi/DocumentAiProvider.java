package com.wikiagent.domain.parse.spi;

import java.util.Set;

/**
 * 文档 AI 供应商 SPI（子项目 B §2.2）。
 * <p>
 * 首批实现：DashScope（OCR/LAYOUT/TABLE 走 qwen-vl 多模态；ASR 走 paraformer 文件转写）；
 * 预留 tencent/baidu/xfyun 适配位。实现必须：
 * <ul>
 *   <li>无 API Key/凭证缺失时 {@link #available()} 返回 false（不阻断应用启动）；</li>
 *   <li>调用失败抛 {@link ParseProviderException} 并正确标记 retryable；</li>
 *   <li>本接口为阻塞调用，超时/重试/熔断由应用层 ProviderExecutor 统一包裹。</li>
 * </ul>
 */
public interface DocumentAiProvider {

    /** 供应商标识：dashscope / tencent / baidu / xfyun / none。 */
    String name();

    /** 该供应商已实现且凭证可用的能力集合。 */
    Set<Capability> capabilities();

    /** 凭证/配置是否就绪（未配置 API Key、缺少公网 URL 配置等返回 false）。 */
    boolean available();

    /** 图片/扫描页 OCR。 */
    OcrResult ocr(OcrRequest request);

    /** 录音文件转写（异步提交+轮询，调用方线程内阻塞完成）。 */
    TranscriptResult transcribe(AudioRequest request);

    /** 单页版面分析。 */
    LayoutResult layout(LayoutRequest request);

    /** 单页表格识别。 */
    TableResult table(TableRequest request);
}
