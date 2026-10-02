package com.wikiagent.domain.rule.dsl;

/**
 * 规则定义或执行错误（未知算子/参数缺失/类型不符/表达式非法）。
 * 引擎为确定性零 LLM：错误直接失败，不做任何猜测性修复。
 */
public class RuleEngineException extends RuntimeException {

    public RuleEngineException(String message) {
        super(message);
    }

    public RuleEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
