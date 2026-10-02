package com.wikiagent.domain.parse.spi;

/**
 * 文档 AI 能力（子项目 B §2.2 SPI）。可插拔供应商按能力注册与选择。
 */
public enum Capability {
    /** 图片/扫描页光学字符识别。 */
    OCR,
    /** 录音文件语音转写。 */
    ASR,
    /** 版面分析（标题/正文/图表/页眉页脚等有序块）。 */
    LAYOUT,
    /** 表格结构识别（行列矩阵 + 单元格坐标）。 */
    TABLE
}
