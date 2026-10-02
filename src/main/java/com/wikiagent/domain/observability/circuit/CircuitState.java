package com.wikiagent.domain.observability.circuit;

/**
 * 子项目 I（AC-I4）统一熔断状态（跨组件归一）。
 */
public enum CircuitState {
    /** 正常：依赖可用或显式启用外部组件。 */
    CLOSED,
    /** 熔断/降级中：依赖故障或未启用，系统正在降级路径运行。 */
    OPEN
}
