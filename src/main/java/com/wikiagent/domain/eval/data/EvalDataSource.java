package com.wikiagent.domain.eval.data;

import java.util.List;

/**
 * 离线评测只读数据集接口（规格 §H）：向七个 {@code MetricCalculator} 提供
 * 窗口过滤前的全量投影行。实现方负责从既有存储/JPA 读取并投影，
 * 计算器只做确定性聚合，不回写、不调用 LLM。
 * <p>
 * 缺数据语义：任一类目返回空列表时，对应计算器必须返回
 * {@code MetricValue.missing}，不得编造数值。
 */
public interface EvalDataSource {

    /** extracted_field 投影（字段准确率数据源）。 */
    List<FieldSample> fields();

    /** metric_event 检索事件投影 + kb_feedback/golden 相关性标签（检索命中率数据源）。 */
    List<RetrievalSample> retrievals();

    /** 引用判定行（引用正确率数据源，由评测运行器产出）。 */
    List<CitationSample> citations();

    /** judge 录制评判（评判错误率数据源）。 */
    List<JudgeVerdict> judgeVerdicts();

    /** review_case 处置投影（人工修改率数据源）。 */
    List<ReviewDisposition> reviewDispositions();

    /** model_call_log 投影（响应时间/单次成本数据源）。 */
    List<ModelCallSample> modelCalls();
}
