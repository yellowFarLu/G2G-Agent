package com.wikiagent.domain.observability.circuit;

/**
 * 子项目 I（AC-I4）统一熔断器注册表的组件名（Prometheus tag component 取值）。
 * 与降级矩阵 docs/operations/degradation-matrix.md 的五类一一对应。
 */
public final class CircuitComponents {

    /** Milvus 向量库（故障→本地中文 bigram 检索）。 */
    public static final String MILVUS = "milvus";
    /** 文档 AI Provider：OCR/版面/表格/ASR（故障→能力熔断跳过）。 */
    public static final String PARSE = "parse";
    /** LLM 聊天模型 Provider（故障→降级候选/NoOp 兜底）。 */
    public static final String LLM = "llm";
    /** Redis 协调（不可用/未启用→MySQL 行字段 + JVM 单机协调、单机限流）。 */
    public static final String REDIS = "redis";
    /** RocketMQ（不可用/未启用→JVM 本地调度 mq=local）。 */
    public static final String ROCKETMQ = "rocketmq";

    /** 注册表固定覆盖的五个组件（无 supplier 时按 CLOSED 处理）。 */
    public static final String[] ALL = {MILVUS, PARSE, LLM, REDIS, ROCKETMQ};

    private CircuitComponents() {
    }
}
