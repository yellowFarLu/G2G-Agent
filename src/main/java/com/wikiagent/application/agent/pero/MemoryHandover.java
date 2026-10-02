package com.wikiagent.application.agent.pero;

import com.wikiagent.domain.agent.Plan;
import com.wikiagent.domain.agent.PlanStep;
import com.wikiagent.domain.agent.ReActResult;
import com.wikiagent.domain.memory.HandoverRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * v6 §20 Handover 端口的内存默认实现（自洽兜底）。
 * <p>
 * 并发安全：字段使用线程安全容器/访问同步（CopyOnWriteArrayList），
 * 避免多 AGENT 任务并发时跨任务状态互串（任务框架下同一 Spring 单例被共享）。
 * <p>
 * 写穿透：init/completeNode/abandonPath 同步写入 {@link HandoverRepository}
 *（默认 MySQL 四表适配器），使交接清单在崩溃恢复后仍可从持久层读回
 *（ReadHandoverTool 读侧即 HandoverRepository.load）；持久化失败仅记日志，不阻断主流程。
 */
@Component
public class MemoryHandover implements Handover {

    private static final Logger log = LoggerFactory.getLogger(MemoryHandover.class);

    private final ObjectProvider<HandoverRepository> repositoryProvider;

    public MemoryHandover(ObjectProvider<HandoverRepository> repositoryProvider) {
        this.repositoryProvider = repositoryProvider;
    }

    private volatile String userId;
    private volatile String sessionId;
    private volatile String userInput;
    private final List<PlanStep> executedNodes = new CopyOnWriteArrayList<>();
    private final List<String> abandonedPaths = new CopyOnWriteArrayList<>();
    private volatile Plan declaredPlan;

    @Override
    public void init(String userId, String sessionId, String userInput) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.userInput = userInput;
        // 单例兜底：新会话初始化时清空上一会话残留，避免跨会话串扰
        this.executedNodes.clear();
        this.abandonedPaths.clear();
        this.declaredPlan = null;
        writeThrough(repo -> repo.init(userId, sessionId, userInput));
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
        writeThrough(repo -> repo.addExecutedNode(userId, sessionId, step.id(), step.goal()));
        log.debug("[handover] completeNode id={} done={}", step.id(), result.done());
    }

    @Override
    public void failNode(PlanStep step, String message) {
        log.warn("[handover] failNode id={} msg={}", step.id(), message);
    }

    @Override
    public void abandonPath(PlanStep step, String reason) {
        abandonedPaths.add(step.id() + ": " + reason);
        writeThrough(repo -> repo.addAbandonedPath(userId, sessionId, step.id(), reason));
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

    /** 写穿透：内存更新后同步落库；失败仅记日志，不阻断主流程。 */
    private void writeThrough(java.util.function.Consumer<HandoverRepository> action) {
        try {
            HandoverRepository repo = repositoryProvider.getIfAvailable();
            if (repo != null) {
                action.accept(repo);
            }
        } catch (Exception e) {
            log.warn("[handover] 写穿透落库失败（非阻断）userId={} sessionId={} err={}",
                    userId, sessionId, e.getMessage());
        }
    }
}
