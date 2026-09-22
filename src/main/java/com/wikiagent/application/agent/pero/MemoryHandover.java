package com.wikiagent.application.agent.pero;

import com.wikiagent.domain.agent.Plan;
import com.wikiagent.domain.agent.PlanStep;
import com.wikiagent.domain.agent.ReActResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * v6 §20 Handover 端口的内存默认实现（自洽兜底）。
 * <p>
 * v3-v5 实施时由 {@code FileHandoverRepository} 替换（持久化到 todo.json +
 * Python field_index.json），本类为 v6 自洽的最简内存实现，便于编译启动。
 * <p>
 * 并发安全：单实例 + 单 Conversation 顺序执行（PeroAgent 主循环是同步的），
 * 多 Agent 并发场景由 §22 HandoffLock 兜底（v3-v5 实施时加锁）。
 */
@Component
public class MemoryHandover implements Handover {

    private static final Logger log = LoggerFactory.getLogger(MemoryHandover.class);

    private String userId;
    private String sessionId;
    private String userInput;
    private final List<PlanStep> executedNodes = new ArrayList<>();
    private final List<String> abandonedPaths = new ArrayList<>();
    private Plan declaredPlan;

    @Override
    public void init(String userId, String sessionId, String userInput) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.userInput = userInput;
        log.debug("[handover] init userId={} sessionId={}", userId, sessionId);
    }

    @Override
    public void declarePlan(Plan plan) {
        this.declaredPlan = plan;
        log.debug("[handover] declarePlan size={}", plan.size());
    }

    @Override
    public void startNode(PlanStep step) {
        log.debug("[handover] startNode id={} goal={}", step.id(), step.goal());
    }

    @Override
    public void completeNode(PlanStep step, ReActResult result) {
        executedNodes.add(step);
        log.debug("[handover] completeNode id={} done={}", step.id(), result.done());
    }

    @Override
    public void failNode(PlanStep step, String message) {
        log.warn("[handover] failNode id={} msg={}", step.id(), message);
    }

    @Override
    public void abandonPath(PlanStep step, String reason) {
        abandonedPaths.add(step.id() + ": " + reason);
        log.info("[handover] abandonPath id={} reason={}", step.id(), reason);
    }

    @Override
    public void persistEvent(String userId, String sessionId, String answer) {
        log.info("[handover] persistEvent userId={} sessionId={} answerLen={}",
                userId, sessionId, answer == null ? 0 : answer.length());
    }

    /** 暴露给 Generator 汇总答案时使用（已执行节点）。 */
    public List<PlanStep> executedNodes() {
        return executedNodes;
    }

    /** 暴露给 Generator 汇总答案时使用（放弃路径）。 */
    public List<String> abandonedPaths() {
        return abandonedPaths;
    }

    public String userInput() {
        return userInput;
    }
}
