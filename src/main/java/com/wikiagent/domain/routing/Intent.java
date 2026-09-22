package com.wikiagent.domain.routing;

/**
 * v1-v2 §7 LLM 路由层 - 意图分类枚举。
 * <p>
 * v3 扩展为 5 类意图：知识问答（真实）+ 4 个 Mock（AI Coding / 客户接入 / 业务规则配置 / 订单查询）。
 */
public enum Intent {
    KNOWLEDGE_QA("knowledge_qa", false),         // 知识问答（简单任务）
    AI_CODING("ai_coding", true),                  // AI Coding（复杂任务，Mock）
    CUSTOMER_INTAKE("customer_intake", true),       // 客户接入（复杂任务，Mock）
    BUSINESS_RULE_CONFIG("business_rule_config", true), // 业务规则配置（复杂任务，Mock）
    ORDER_QUERY("order_query", false);             // 订单查询（简单任务，Mock）

    private final String code;
    private final boolean complex;

    Intent(String code, boolean complex) {
        this.code = code;
        this.complex = complex;
    }

    public String code() { return code; }
    public boolean isComplex() { return complex; }

    public static Intent fromCode(String code) {
        for (Intent i : values()) {
            if (i.code.equalsIgnoreCase(code)) return i;
        }
        return KNOWLEDGE_QA; // 降级默认
    }
}
