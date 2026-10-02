package com.wikiagent.domain.eval.golden;

/**
 * 异常路径黄金样本（anomaly-golden.json 数组元素）。
 *
 * @param id        样本 id
 * @param scenario  ENCRYPTED / CORRUPT / OVERSIZE / LOW_CONFIDENCE / REVIEW_REJECT
 * @param domain    九大业务域标签
 * @param expects    异常处置期望（只断言出现的键）：
 *                  taskStatus（任务状态机）/ documentStatus（kb_document.status）/
 *                  humanKind（HumanTaskKind）/ errorCode（ErrorCode）
 * @param note      场景补充说明
 */
public record AnomalyGoldenCase(String id,
                                String scenario,
                                String domain,
                                Expects expects,
                                String note) {

    public record Expects(String taskStatus,
                          String documentStatus,
                          String humanKind,
                          String errorCode) {
    }
}
