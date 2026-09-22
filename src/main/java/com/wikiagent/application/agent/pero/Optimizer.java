package com.wikiagent.application.agent.pero;

import com.wikiagent.domain.agent.Plan;
import com.wikiagent.domain.agent.Reflection;

/**
 * v6 §20.5 剩余 Plan 动态调整接口，对应 LangGraph Re-Plan Step（§20.2 #6）。
 * <p>
 * 吸收 Self-Refine（arXiv:2303.17651）+ LangGraph 官方 Plan-and-Execute template 的 Re-Plan 思想：
 * 节点完成后，根据 {@link Reflection} 中 {@code planAdjustments} 增删改剩余未执行步骤。
 * <p>
 * 幂等性保证：
 * <ul>
 *   <li>已执行节点不入 {@link Plan#steps()}，{@code optimize} 只改 remaining，不影响 handover 历史</li>
 *   <li>{@code wikiagent.pero.optimize.enabled=false} 时退化为 §2 固定 plan，本接口不被调用</li>
 * </ul>
 */
public interface Optimizer {

    /**
     * 据反思动态调整剩余 Plan。
     *
     * @param remaining  当前剩余未执行的 Plan
     * @param reflection 节点反思结果（可能含 planAdjustments）
     * @return 调整后的 Plan（新实例，原 Plan 不变以便 handover 追踪）
     */
    Plan optimize(Plan remaining, Reflection reflection);
}
