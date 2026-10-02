package com.wikiagent.application.agent.pero;

import com.wikiagent.domain.agent.Plan;
import com.wikiagent.domain.agent.PlanStep;
import com.wikiagent.domain.agent.ReActResult;
import com.wikiagent.domain.agent.Reflection;
import com.wikiagent.domain.task.ControlSignalException;
import com.wikiagent.domain.task.FatalTaskException;
import com.wikiagent.domain.task.HumanRequiredException;
import com.wikiagent.service.chat.SseSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * v6 §20.4 Plan-Execute-Reflect-Optimize 主循环（PERO）。
 * <p>
 * 结合 Plan-And-Execute（arXiv:2305.04091）+ ReAct（arXiv:2210.03629）+ Reflexion（arXiv:2303.11366）
 * + Self-Refine（arXiv:2303.17651）+ LangGraph Re-Plan（§20.2 #6）的优点：
 * <ol>
 *   <li><b>Perceive</b>：感知用户输入、用户档案、短期记忆、意图分类（§2.3 同）</li>
 *   <li><b>Plan</b>：一次性产出 {@link Plan}（Plan-and-Solve 论文）</li>
 *   <li><b>Execute(ReAct)</b>：节点内 ReAct 子循环执行（{@link ReActExecutor}）</li>
 *   <li><b>Reflect</b>：节点完成后反思，写入 episodic memory（Reflexion 论文）</li>
 *   <li><b>Optimize</b>：据反思动态调整剩余 Plan（Self-Refine + LangGraph Re-Plan）</li>
 *   <li><b>Loop</b>：回到 step 3 直到 plan.steps() 空，最后 generate 答案</li>
 * </ol>
 * <p>
 * 开关：{@code wikiagent.pero.enabled=false} 时回退 §2 AgentOrchestrator（由 {@code ChatService} 路由）。
 * <p>
 * 与 §2 {@code AgentOrchestrator} 的区别：§2 是单 Agent 顺序执行 + 单步 NodeExecutor + 一次性 Reflexion；
 * v6 把 NodeExecutor 替换为节点内 ReAct 多轮循环，把 Reflexion 拓展为 Reflect→Optimize 闭环。
 */
@Service
public class PeroAgent {

    private static final Logger log = LoggerFactory.getLogger(PeroAgent.class);

    private final PeroPlanner peroPlanner;
    private final ReActExecutor reactExecutor;
    private final Reflector reflector;
    private final Optimizer optimizer;
    private final EpisodicMemory episodicMemory;
    private final TraceService trace;
    private final Handover handover;
    private final Generator generator;
    private final int maxIter;

    public PeroAgent(PeroPlanner peroPlanner,
                     ReActExecutor reactExecutor,
                     Reflector reflector,
                     Optimizer optimizer,
                     EpisodicMemory episodicMemory,
                     TraceService trace,
                     Handover handover,
                     Generator generator,
                     @Value("${wikiagent.pero.react.max-iterations:8}") int maxIter) {
        this.peroPlanner = peroPlanner;
        this.reactExecutor = reactExecutor;
        this.reflector = reflector;
        this.optimizer = optimizer;
        this.episodicMemory = episodicMemory;
        this.trace = trace;
        this.handover = handover;
        this.generator = generator;
        this.maxIter = maxIter;
    }

    /**
     * 运行 PERO 主循环。
     * <p>
     * 异常向上抛出，由 {@code ChatService} 统一转 SSE error 事件。
     */
    public void run(String userId, String sessionId, String userInput, SseSender sse) {
        Perception ctx = perceive(userId, sessionId, userInput);
        Plan plan = planPhase(ctx, userId, userInput);
        executeLoop(ctx, plan, handover, sse, PeroLoopHook.NOOP);
        generatePhase(ctx, handover, userId, sessionId, sse);
    }

    /**
     * Plan 阶段（感知 + 规划 + 交接清单声明），供任务框架 PLAN 步复用。
     * <p>
     * 返回的 {@link Plan} 为可变剩余计划：调用方如需自持副本请自行拷贝
     * （任务框架 AgentTaskHandler 以 JSON checkpoint 形式持有，不依赖内部可变 list）。
     */
    public Plan runPlan(String userId, String sessionId, String userInput) {
        Perception ctx = perceive(userId, sessionId, userInput);
        return planPhase(ctx, userId, userInput);
    }

    private Plan planPhase(Perception ctx, String userId, String userInput) {
        String conversationId = userId + ":" + ctx.sessionId();
        TraceSpan planSpan = trace.start(conversationId, userId, "plan", userInput);
        Plan plan = peroPlanner.plan(ctx);
        handover.init(userId, ctx.sessionId(), userInput);
        handover.declarePlan(plan);
        trace.end(planSpan, plan.toString(), "OK", null);
        log.debug("PERO plan 生成: {} 个步骤", plan.size());
        return plan;
    }

    /**
     * EXECUTE→REFLECT→OPTIMIZE 节点循环（v6 §20.4 步骤 3-5），供 run 与任务框架 NODE 步复用。
     * <p>
     * 钩子语义：节点循环顶部调 {@code hook.beforeNode}；ReAct 迭代 gate 为
     * {@code () -> hook.afterReactIteration(step, i)}。钩子抛出的
     * {@code ControlSignalException}（暂停/取消）先于通用 catch 重抛——
     * 节点不 failNode、不进入失败反思/重试。
     */
    public void executeLoop(Perception ctx, Plan plan, Handover handover,
                            SseSender sse, PeroLoopHook hook) {
        executeLoop(ctx, plan, handover, sse, hook, null);
    }

    /**
     * F 治理版重载：携带 {@link ReActGovernance}（权限/审批/预算），
     * 原五参方法委托 null 治理（v6 聊天路径行为不变）。
     */
    public void executeLoop(Perception ctx, Plan plan, Handover handover,
                            SseSender sse, PeroLoopHook hook, ReActGovernance governance) {
        String conversationId = ctx.userId() + ":" + ctx.sessionId();
        int nodeIndex = 0;
        while (!plan.steps().isEmpty()) {
            PlanStep step = plan.steps().remove(0);
            hook.beforeNode(step, nodeIndex++);
            TraceSpan nodeSpan = trace.start(conversationId, ctx.userId(), step.id(), step.goal());
            handover.startNode(step);

            try {
                // 3. EXECUTE：节点内部用 ReAct (Thought/Action/Observation) 循环
                java.util.concurrent.atomic.AtomicInteger iter = new java.util.concurrent.atomic.AtomicInteger();
                ReActResult result = reactExecutor.execute(step, ctx, handover, maxIter,
                        () -> hook.afterReactIteration(step, iter.incrementAndGet()), governance);
                handover.completeNode(step, result);

                // 4. REFLECT：让 LLM 复盘节点结果（Reflexion 论文 §20.2 #3）
                Reflection reflection = reflector.reflect(step, result, ctx);
                episodicMemory.put(reflection);

                // 5. OPTIMIZE：据反思动态调整剩余 Plan（Self-Refine + LangGraph Re-Plan §20.2 #5/#6）
                if (reflection.needsRework()) {
                    log.debug("节点 {} 需要重做，携带 hint 进入下一轮 ReAct", step.id());
                    ReActResult redone = reactExecutor.execute(step, ctx.with(reflection), handover, maxIter,
                            () -> { }, governance);
                    handover.completeNode(step, redone);
                }
                trace.end(nodeSpan, result.toString(), result.done() ? "OK" : "TRUNCATED", null);
                plan = optimizer.optimize(plan, reflection);
            } catch (ControlSignalException c) {
                // 暂停/取消：原样穿出，节点不 failNode（Task 12 冻结语义）
                throw c;
            } catch (HumanRequiredException | FatalTaskException g) {
                // F2/F3 治理信号（预算超限转人工/致命失败、高危工具批准）：原样穿出，
                // 不进入失败反思/重试，由任务框架 worker 建人工任务或终态
                throw g;
            } catch (Exception e) {
                handover.failNode(step, e.getMessage());
                trace.end(nodeSpan, null, "ERROR", e.getMessage());
                log.warn("节点 {} 执行失败: {}", step.id(), e.getMessage());

                // Reflexion：失败时反思是否重试
                Reflection reflection = reflector.reflectOnFailure(step, e, ctx);
                episodicMemory.put(reflection);
                if (reflection.shouldRetry()) {
                    plan.steps().add(0, step); // 重新入队等下一轮重试
                } else {
                    handover.abandonPath(step, "失败不重试：" + reflection.reason());
                }
            }
        }
    }

    /**
     * GENERATE 阶段：汇总节点产物生成最终答案 + 交接清单持久化 + SSE 推送，
     * 供 run 与任务框架 GENERATE 步复用。
     *
     * @return 最终答案文本
     */
    public String generatePhase(Perception ctx, Handover handover,
                                String userId, String sessionId, SseSender sse) {
        String answer = generator.generate(ctx, handover);
        handover.persistEvent(userId, sessionId, answer);
        sse.send("delta", Map.of("text", answer));
        sse.send("done", Map.of());
        return answer;
    }

    /**
     * Perceive 阶段：构造感知上下文。
     * <p>
     * v3-v5 实施时由 {@code PerceptionService.perceive()} 替换：
     * 加载用户档案（§5）+ 短期记忆（§3）+ 意图分类（§7 LLM 路由）。
     * v6 默认实现使用 {@link SimplePerception}（intent=knowledge_qa）。
     * public 可见性供任务框架各步骤独立重建上下文（SimplePerception 构造为纯内存操作）。
     */
    public Perception perceive(String userId, String sessionId, String userInput) {
        return new SimplePerception(userId, sessionId, userInput);
    }
}
