package com.wikiagent.domain.observability.circuit;

/**
 * 子项目 I（AC-I4）熔断器状态供给 SPI：各第三方组件的适配器在 observability 层实现，
 * <b>不修改业务类</b>（优先用既有公开 getter；拿不到的状态用反射只读或标记 CLOSED+javadoc 待补）。
 * <p>
 * 实现必须轻量、无外部 IO（状态读取为内存操作），并在任何异常时回退 {@link CircuitState#CLOSED}。
 */
public interface CircuitStateSupplier {

    /** 组件名，取 {@link CircuitComponents} 常量。 */
    String component();

    /** 当前归一化状态。 */
    CircuitState state();
}
