package com.wikiagent.application.eval.support;

import com.wikiagent.domain.parse.spi.AudioRequest;
import com.wikiagent.domain.parse.spi.BBox;
import com.wikiagent.domain.parse.spi.Capability;
import com.wikiagent.domain.parse.spi.DocumentAiProvider;
import com.wikiagent.domain.parse.spi.LayoutBlock;
import com.wikiagent.domain.parse.spi.LayoutBlockType;
import com.wikiagent.domain.parse.spi.LayoutRequest;
import com.wikiagent.domain.parse.spi.LayoutResult;
import com.wikiagent.domain.parse.spi.OcrRequest;
import com.wikiagent.domain.parse.spi.OcrResult;
import com.wikiagent.domain.parse.spi.OcrSpan;
import com.wikiagent.domain.parse.spi.TableCell;
import com.wikiagent.domain.parse.spi.TableRequest;
import com.wikiagent.domain.parse.spi.TableResult;
import com.wikiagent.domain.parse.spi.TranscriptResult;
import com.wikiagent.domain.parse.spi.TranscriptSegment;

import java.util.List;
import java.util.Set;

/**
 * 离线评测专用文档 AI 录制桩：OCR/ASR/LAYOUT/TABLE 全部返回确定性常量，
 * 无网络、无 API key、无时钟/随机依赖（规格 §H：全部用录制固件/桩）。
 * <p>
 * 这不是生产供应商实现，仅供 {@code wikiagent.eval.enabled=true} 的离线评测装配使用。
 */
public class OfflineEvalAiProvider implements DocumentAiProvider {

    /** 录制 OCR 文本（同时覆盖报关扫描件与结算单图片断言词）。 */
    public static final String OCR_TEXT = "EVAL-OCR 离线桩识别文本：报关单扫描件 / 结算单图片";
    /** 录制 ASR 文本。 */
    public static final String ASR_TEXT = "评测离线转写文本（结算语音）";

    @Override
    public String name() {
        return "eval-offline-stub";
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.values());
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public OcrResult ocr(OcrRequest request) {
        return new OcrResult(OCR_TEXT,
                List.of(new OcrSpan(OCR_TEXT, BBox.of(1, 2, 200, 40), 0.91)), 0.91);
    }

    @Override
    public TranscriptResult transcribe(AudioRequest request) {
        return new TranscriptResult(ASR_TEXT,
                List.of(new TranscriptSegment(0, 500, "评测"),
                        new TranscriptSegment(500, 1000, "转写")), 1.0);
    }

    @Override
    public LayoutResult layout(LayoutRequest request) {
        // 录制版面：每页给一个 TABLE 块（跨页表格样本据此触发逐页 TABLE 识别与拼接）
        return new LayoutResult(List.of(
                new LayoutBlock(0, LayoutBlockType.TABLE, "录制表格块"),
                new LayoutBlock(1, LayoutBlockType.TEXT, "录制正文块")));
    }

    @Override
    public TableResult table(TableRequest request) {
        int pageNo = request.pageNo();
        if (pageNo <= 1) {
            return new TableResult(1, 2, 2, List.of(
                    new TableCell(0, 0, "商品编码"),
                    new TableCell(0, 1, "商品名称"),
                    new TableCell(1, 0, "1234"),
                    new TableCell(1, 1, "工业轴承")));
        }
        // 第二页带重复表头（TableStitcher 应去重一行并标 ambiguous）
        return new TableResult(2, 2, 2, List.of(
                new TableCell(0, 0, "商品编码"),
                new TableCell(0, 1, "商品名称"),
                new TableCell(1, 0, "5678"),
                new TableCell(1, 1, "密封胶条")));
    }
}
