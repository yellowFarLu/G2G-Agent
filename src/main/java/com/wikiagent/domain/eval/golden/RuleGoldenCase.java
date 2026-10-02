package com.wikiagent.domain.eval.golden;

import java.util.Map;

/**
 * 规则黄金样本（rule-golden.json 数组元素）。
 *
 * @param id             样本 id
 * @param ruleCode       规则集 code（七算子覆盖：map/const/arith/regex/enum/compare/materialDiff）
 * @param domain         九大业务域标签
 * @param dslFixture     DSL 程序夹具 classpath 路径（steps + outputs 声明）
 * @param input          规则输入快照
 * @param expectedOutput 期望输出子集（与 RuleOutcome.outputs() 精确比对）
 */
public record RuleGoldenCase(String id,
                             String ruleCode,
                             String domain,
                             String dslFixture,
                             Map<String, Object> input,
                             Map<String, Object> expectedOutput) {
}
