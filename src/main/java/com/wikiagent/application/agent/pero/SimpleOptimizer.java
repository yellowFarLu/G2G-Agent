package com.wikiagent.application.agent.pero;

import com.wikiagent.domain.agent.Plan;
import com.wikiagent.domain.agent.PlanStep;
import com.wikiagent.domain.agent.Reflection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * v6 §20.5 Optimizer 的默认实现（Self-Refine + LangGraph Re-Plan 等价）。
 * <p>
 * 消费 {@link Reflection#planAdjustments()} 调整剩余 Plan：
 * <ul>
 *   <li>{@code planAdjustments} 为 null/空 → 原样返回 remaining</li>
 *   <li>非空 → 用 planAdjustments 替换 remaining.steps()</li>
 * </ul>
 * 幂等性保证：已执行节点不入 remaining，{@code optimize} 只改未执行部分。
 * <p>
 * 开关：{@code wikiagent.pero.optimize.enabled=false} 时退化为 {@link NoOpOptimizer}，
 * 不消费 planAdjustments，对应 §13.8 验收 #24 "PERO 开关回退"测试用例。
 * <p>
 * v3-v5 实施时可用更复杂的 LLM 重规划替换；本类为 v6 自洽默认实现。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.pero.optimize.enabled",
        havingValue = "true", matchIfMissing = true)
public class SimpleOptimizer implements Optimizer {

    private static final Logger log = LoggerFactory.getLogger(SimpleOptimizer.class);

    /** 包级构造器，便于单元测试。 */
    SimpleOptimizer() {
    }

    @Override
    public Plan optimize(Plan remaining, Reflection reflection) {
        if (reflection == null || reflection.planAdjustments() == null
                || reflection.planAdjustments().isEmpty()) {
            return remaining;
        }
        List<PlanStep> adjusted = new ArrayList<>(reflection.planAdjustments());
        log.debug("Optimizer 替换剩余 Plan：原 {} 步 → 新 {} 步",
                remaining.size(), adjusted.size());
        remaining.replaceAll(adjusted);
        return remaining;
    }
}
