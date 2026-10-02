package com.wikiagent.domain.eval.anomaly;

import com.wikiagent.domain.task.ErrorCode;
import com.wikiagent.domain.task.HumanTaskKind;
import com.wikiagent.domain.task.TaskStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 异常分类器（纯 Java，零 Spring）。
 * <p>
 * 本类是“异常信号 → 任务/文档/人工/错误码”契约的<b>单一编码处</b>，与生产处置映射
 * （{@code IngestTaskHandler#parse/#extract} 的 catch 块 + worker 状态机）保持一致：
 * <ul>
 *   <li>{@code EncryptedDocumentException} → HumanRequired(DECRYPT) → WAITING_HUMAN；</li>
 *   <li>{@code IllegalArgumentException}（含超大文件）→ Fatal(VALIDATION_FAILED) → FAILED；</li>
 *   <li>{@code IllegalStateException}（损坏 PDF）→ Fatal(PARSE_FAILED) → FAILED；</li>
 *   <li>抽取 report.needsReview() → HumanRequired(REVIEW) → WAITING_HUMAN；</li>
 *   <li>人工复核处置（APPROVE/EDIT/REJECT）resolve 人工任务后 worker 恢复执行 → RUNNING。</li>
 * </ul>
 * 评测器先用<b>真实组件</b>（RichDocumentParser/ParseInputValidator/FieldExtractionService）
 * 产生异常/信号，再经本分类器得到结果，与 golden 期望比对——不在桩里直接写期望。
 */
public final class AnomalyClassifier {

    /** 加密 PDF（口令被拒与否都建 DECRYPT；passwordRejected 仅影响表单文案）。 */
    public AnomalyOutcome encrypted(boolean passwordRejected) {
        return AnomalyOutcome.taskHuman(
                TaskStatus.WAITING_HUMAN.name(), HumanTaskKind.DECRYPT.name());
    }

    /** 损坏文件：IllegalStateException → PARSE_FAILED。 */
    public AnomalyOutcome corrupt() {
        return AnomalyOutcome.failed(ErrorCode.PARSE_FAILED.name());
    }

    /** 入口校验失败（不支持扩展/空文件/超大）：IllegalArgumentException → VALIDATION_FAILED。 */
    public AnomalyOutcome validationRejected() {
        return AnomalyOutcome.failed(ErrorCode.VALIDATION_FAILED.name());
    }

    /** 低置信/校验失败抽取：HumanRequired(REVIEW) → WAITING_HUMAN。 */
    public AnomalyOutcome lowConfidence() {
        return AnomalyOutcome.taskHuman(
                TaskStatus.WAITING_HUMAN.name(), HumanTaskKind.REVIEW.name());
    }

    /**
     * 人工复核处置后恢复流水线。三种处置（APPROVE/EDIT/REJECT）均 resolve 人工任务，
     * worker 从 WAITING_HUMAN 恢复为 RUNNING（驳回语义为“不采纳模型结果并留痕”，
     * 任务本身不失败——见 ReviewCaseService#resumeReviewHumanTask）。
     */
    public AnomalyOutcome afterReviewDisposition(String action) {
        return new AnomalyOutcome(TaskStatus.RUNNING.name(), null, null, null);
    }

    /**
     * 期望（golden，非 null 字段）与实际结果逐字段匹配；返回不匹配说明列表（空=通过）。
     */
    public List<String> mismatches(AnomalyOutcome expected, AnomalyOutcome actual) {
        List<String> diffs = new ArrayList<>();
        check(diffs, "taskStatus", expected.taskStatus(), actual.taskStatus());
        check(diffs, "documentStatus", expected.documentStatus(), actual.documentStatus());
        check(diffs, "humanKind", expected.humanKind(), actual.humanKind());
        check(diffs, "errorCode", expected.errorCode(), actual.errorCode());
        return diffs;
    }

    private static void check(List<String> diffs, String field, String expected, String actual) {
        if (expected != null && !Objects.equals(expected, actual)) {
            diffs.add(field + " 期望=" + expected + " 实际=" + actual);
        }
    }
}
