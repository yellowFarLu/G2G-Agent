package com.wikiagent.application.eval.support;

import org.springframework.beans.factory.ObjectProvider;

import java.util.Collections;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 评测离线装配用的极简 {@link ObjectProvider}：包装固定单例（可为 null 表示无 Bean）。
 * 仅用于在 main 代码中手工 new {@code RetrievalService}（生产装配仍走 Spring）。
 */
public class InstanceObjectProvider<T> implements ObjectProvider<T> {

    private final T instance;

    public InstanceObjectProvider(T instance) {
        this.instance = instance;
    }

    @Override
    public T getObject() {
        return instance;
    }

    @Override
    public T getObject(Object... args) {
        return instance;
    }

    @Override
    public T getIfAvailable() {
        return instance;
    }

    @Override
    public T getIfUnique() {
        return instance;
    }

    @Override
    public Iterator<T> iterator() {
        return instance == null
                ? Collections.emptyIterator()
                : Collections.singleton(instance).iterator();
    }

    @Override
    public void forEach(Consumer<? super T> action) {
        if (instance != null) {
            action.accept(instance);
        }
    }

    @Override
    public Spliterator<T> spliterator() {
        return Spliterators.spliterator(iterator(),
                instance == null ? 0 : 1, Spliterator.ORDERED);
    }

    @Override
    public Stream<T> stream() {
        return instance == null ? Stream.empty() : Stream.of(instance);
    }
}
