package com.wikiagent.application.agent.pero;

import com.wikiagent.domain.agent.Reflection;

import java.util.List;

/**
 * v6 §20.5 反思记忆接口（Reflexion 论文 arXiv:2303.11366 中的 episodic memory）。
 * <p>
 * 反思文本写入 episodic memory 跨会话复用：下一轮 trial（相同 userId + intent）启动时，
 * 通过 {@link #recall} 取出历史反思作为 hint 传入下一轮 ReAct，避免重复犯错。
 * <p>
 * 实施示例：{@code RedisEpisodicMemoryAdapter} 用 Redis List 存储，
 * key 为 {@code wikiagent:episodic:{userId}:{intent}}，TTL 30 天
 * （对应 {@code wikiagent.pero.reflect.episodic-memory-ttl-days}）。
 */
public interface EpisodicMemory {

    /** 写入反思。 */
    void put(Reflection r);

    /** 跨会话复用反思：按 (userId, intent) 取最近 N 条。 */
    List<Reflection> recall(String userId, String intent);
}
