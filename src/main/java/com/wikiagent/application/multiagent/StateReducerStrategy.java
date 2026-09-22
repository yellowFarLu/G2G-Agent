package com.wikiagent.application.multiagent;

import com.wikiagent.infrastructure.state.StateReducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v6 §22.5 状态合并策略注册（§22.3 #4 LangGraph AnnotatedReducer 等价自研实现）。
 * <p>
 * <b>实施校正</b>（遵循"禁止捏造事实"约束）：
 * §22.5 骨架设想调用 {@code OverAllState.registerKeyAndStrategy(k, reducer)}
 * 注册 reducer，但 OverAllState 类型属于 spring-ai-alibaba-graph（1.1.2.0 实测已重构为
 * spring-ai-alibaba-agent-framework，OverAllState 的具体方法签名未在官方 Javadoc 完全核实），
 * 本类改用本地 {@link ConcurrentHashMap} 注册 reducer，对外提供 {@link #reducerFor(String)} 查询接口，
 * 语义与 LangGraph AnnotatedReducer 等价（按 key 声明式合并策略）。
 * <p>
 * 后续若要切换到 spring-ai-alibaba-agent-framework 的真实 OverAllState API，
 * 只需在 {@link #register(String, StateReducer)} 内追加 delegate 调用即可。
 * <p>
 * 默认注册的 3 个 key（与 §22.5 骨架一致）：
 * <ul>
 *   <li>{@code input} — {@link StateReducer#overwrite()}（覆盖）</li>
 *   <li>{@code messages} — {@link StateReducer#add()}（追加）</li>
 *   <li>{@code episodic_memory} — {@link StateReducer#add()}（追加）</li>
 * </ul>
 */
@Component
public class StateReducerStrategy {

    private static final Logger log = LoggerFactory.getLogger(StateReducerStrategy.class);

    private final Map<String, StateReducer<?>> registry = new ConcurrentHashMap<>();

    public StateReducerStrategy() {
        // §22.5 骨架默认注册（per-tenant 状态隔离）
        register("input", StateReducer.overwrite());
        register("messages", StateReducer.add());
        register("episodic_memory", StateReducer.add());
        log.info("StateReducerStrategy 初始化: 注册 {} 个 reducer", registry.size());
    }

    /** 注册 key 与 reducer（启动期一次性调用，运行时只读）。 */
    public <T> void register(String key, StateReducer<T> reducer) {
        registry.put(key, reducer);
    }

    /** 查询 key 对应 reducer，未注册返回 null。 */
    @SuppressWarnings("unchecked")
    public <T> StateReducer<T> reducerFor(String key) {
        return (StateReducer<T>) registry.get(key);
    }

    /** 已注册的 key 集合（用于 §13.8 #26 验收断言）。 */
    public java.util.Set<String> registeredKeys() {
        return registry.keySet();
    }

    /**
     * 合并两个版本的状态字段值（按 key 的 reducer 策略）。
     * 未注册的 key 默认用 overwrite 语义（last-write-wins）。
     */
    @SuppressWarnings("unchecked")
    public <T> T merge(String key, T left, T right) {
        StateReducer<T> r = (StateReducer<T>) registry.get(key);
        if (r == null) {
            r = StateReducer.overwrite();
        }
        return r.merge(left, right);
    }
}
