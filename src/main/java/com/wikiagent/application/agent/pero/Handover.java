package com.wikiagent.application.agent.pero;

import com.wikiagent.domain.agent.Plan;
import com.wikiagent.domain.agent.PlanStep;
import com.wikiagent.domain.agent.ReActResult;

/**
 * v6 §20 PERO 主循环的交接清单端口。
 * <p>
 * §4 交接清单设计：每个任务节点生成 handover checklist 并实时更新，存 todo.json，
 * Python 脚本刷新 field_index.json（§4.4）；handover 必须包含：
 * <ul>
 *   <li>用户原始请求</li>
 *   <li>已执行节点描述</li>
 *   <li>放弃的路径（abandoned paths）</li>
 *   <li>数据引用索引（field index）</li>
 * </ul>
 * <p>
 * 本接口作为端口契约（v3-v5 实施时具体化为 {@code FileHandoverRepository}），
 * v6 主循环只依赖以下最小方法。所有方法应保证线程安全（§22 并发控制）。
 */
public interface Handover {

    /** 初始化 handover，记录用户原始请求。 */
    void init(String userId, String sessionId, String userInput);

    /** 声明 Plan 阶段产出的任务计划列表。 */
    void declarePlan(Plan plan);

    /** 节点开始执行。 */
    void startNode(PlanStep step);

    /** 节点正常完成。 */
    void completeNode(PlanStep step, ReActResult result);

    /** 节点失败。 */
    void failNode(PlanStep step, String message);

    /** 放弃该路径，记录原因到 abandoned paths。 */
    void abandonPath(PlanStep step, String reason);

    /** 持久化最终事件到历史事件库（§6 Milvus 父子索引）。 */
    void persistEvent(String userId, String sessionId, String answer);
}
