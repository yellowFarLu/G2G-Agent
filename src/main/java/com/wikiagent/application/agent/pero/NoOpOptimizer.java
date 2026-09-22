package com.wikiagent.application.agent.pero;

import com.wikiagent.domain.agent.Plan;
import com.wikiagent.domain.agent.Reflection;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * v6 §20.5 关闭 Re-Plan 时的兜底 Optimizer：原样返回 Plan，不消费 planAdjustments。
 * <p>
 * 开关：{@code wikiagent.pero.optimize.enabled=false} 时生效，
 * 退化为 §2 固定 plan 执行（不动态调整剩余 Plan）。
 * 对应 §13.8 验收 #24 "PERO 开关回退"测试用例。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.pero.optimize.enabled", havingValue = "false")
public class NoOpOptimizer implements Optimizer {

    @Override
    public Plan optimize(Plan remaining, Reflection reflection) {
        return remaining;
    }
}
