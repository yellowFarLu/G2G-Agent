package com.wikiagent.infrastructure.state;

import java.util.ArrayList;
import java.util.List;

/**
 * v6 §22.5 状态合并接口（类比 LangGraph AnnotatedReducer §22.3 #4）。
 * <p>
 * LangGraph 用 Python 装饰器 {@code Annotated[list, operator.add]} 声明 reducer；
 * Java 无对应语法，本接口用 @FunctionalInterface + 静态工厂方法等价实现：
 * <ul>
 *   <li>{@link #overwrite()} — 后写覆盖前写（last-write-wins，单值字段）</li>
 *   <li>{@link #add()} — 列表追加去重（messages / episodic_memory 等历史字段）</li>
 * </ul>
 * 用法：
 * <pre>{@code
 * StateReducer<String> r = StateReducer.overwrite();
 * String merged = r.merge("old", "new");  // "new"
 *
 * StateReducer<List<String>> addR = StateReducer.add();
 * List<String> m = addR.merge(List.of("a"), List.of("b"));  // ["a", "b"]
 * }</pre>
 * <p>
 * 实施校正：§22.5 骨架用 abstract class，但 Java abstract class 不能用 lambda 实现；
 * 改为 @FunctionalInterface 等价（语义不变，骨架示例代码可保持原样）。
 *
 * @param <T> 状态字段类型
 */
@FunctionalInterface
public interface StateReducer<T> {

    /**
     * 合并两个版本的状态字段值。
     *
     * @param left  旧值（已存在状态）
     * @param right 新值（本次写入）
     * @return 合并后的值
     */
    T merge(T left, T right);

    /** 后写覆盖前写（last-write-wins）。 */
    static <T> StateReducer<T> overwrite() {
        return (l, r) -> r;
    }

    /** 列表追加合并（不去重，调用方需保证元素唯一性）。 */
    static <T> StateReducer<List<T>> add() {
        return (l, r) -> {
            List<T> merged = new ArrayList<>(l == null ? List.of() : l);
            if (r != null) {
                merged.addAll(r);
            }
            return merged;
        };
    }
}
