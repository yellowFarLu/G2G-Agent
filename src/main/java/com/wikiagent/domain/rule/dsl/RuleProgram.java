package com.wikiagent.domain.rule.dsl;

import java.util.List;

/**
 * 解析后的规则程序：步骤序列（按声明顺序执行）+ 输出键列表（从中间量中挑出作为最终输出）。
 */
public record RuleProgram(List<RuleStep> steps, List<String> outputs) {

    public RuleProgram {
        steps = steps == null ? List.of() : List.copyOf(steps);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
    }
}
