package com.wikiagent.application.task.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wikiagent.application.agent.ToolPermissionRegistry;
import com.wikiagent.application.agent.pero.BudgetTracker;
import com.wikiagent.application.agent.pero.PeroAgent;
import com.wikiagent.application.agent.pero.PeroLoopHook;
import com.wikiagent.application.agent.pero.Perception;
import com.wikiagent.application.agent.pero.ReActGovernance;
import com.wikiagent.application.task.TaskBudgetPersistencePort;
import com.wikiagent.application.task.TaskControlContext;
import com.wikiagent.application.task.TaskStreamBus;
import com.wikiagent.domain.agent.Plan;
import com.wikiagent.domain.agent.PlanStep;
import com.wikiagent.domain.task.TaskBudget;
import com.wikiagent.domain.tool.ToolCaller;
import com.wikiagent.domain.task.ErrorCode;
import com.wikiagent.domain.task.FatalTaskException;
import com.wikiagent.domain.task.HumanRequiredException;
import com.wikiagent.domain.task.RetryableTaskException;
import com.wikiagent.domain.task.StepDef;
import com.wikiagent.domain.task.StepResult;
import com.wikiagent.domain.task.TaskExecutionContext;
import com.wikiagent.domain.task.TaskHandler;
import com.wikiagent.domain.task.ports.HumanTaskRepositoryPort;
import com.wikiagent.application.agent.pero.Handover;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent(PERO) 任务处理器（taskType=AGENT，Task 12）。
 * <p>
 * 【边界标记】{@link com.wikiagent.domain.task.BoundaryType#AGENT} — Agent 自主决策路径，
 * 节点内 ReAct 循环，需 F1 工具权限 + F2 任务预算 + F3 工具批准 全链路治理。
 * <p>
 * 步骤语义（A 阶段冻结）：{@code PLAN}(感知+规划，checkpoint 存整份 plan JSON)
 * → 每个_PLANStep 动态注册 {@code NODE_{i}} 步（单节点执行 executeLoop，
 * 暂停/取消在节点开始前与每次 ReAct 迭代顶部生效）
 * → {@code GENERATE}（生成最终答案并把 delta/done 转发到任务流）。
 * <p>
 * 恢复语义：PLAN 的 checkpoint（整份 plan JSON）为唯一计划真相——
 * 恢复时不重新规划（planner 不再调用）；worker 按 firstNonDone 跳过 completed NODE、
 * 重跑被中断的 NODE。handler 每步从 ctx checkpoint 读回计划，不依赖 PERO 内部可变 list。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.task.enabled", havingValue = "true")
public class AgentTaskHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 静态步骤号：PLAN=1，动态 NODE=100+i，GENERATE=1000（升序执行）。 */
    public static final int PLAN_STEP_NO = 1;
    public static final int NODE_BASE = 100;
    public static final int GENERATE_STEP_NO = 1000;
    /** 动态节点上限（含 planAdjustments）：超出部分跳过并告警。 */
    private static final int MAX_NODES = 800;

    private final PeroAgent peroAgent;
    private final Handover handover;
    private final TaskStreamBus streamBus;
    /** F1：工具权限注册表（可为 null：缺省时 NODE 步不做权限拦截）。 */
    private final ToolPermissionRegistry permissionRegistry;
    /** F2：预算持久化端口（worker 注入；缺省时预算仅在内存计量不落库）。 */
    private final TaskBudgetPersistencePort budgetPersistence;
    /** F3：人工任务仓储（读 RESOLVED TOOL_APPROVAL 决策；可为 null，缺省时审批门恒未决）。 */
    private final HumanTaskRepositoryPort humanRepo;

    public AgentTaskHandler(PeroAgent peroAgent, Handover handover, TaskStreamBus streamBus) {
        this(peroAgent, handover, streamBus, null, null, null);
    }

    public AgentTaskHandler(PeroAgent peroAgent, Handover handover, TaskStreamBus streamBus,
                            ToolPermissionRegistry permissionRegistry,
                            TaskBudgetPersistencePort budgetPersistence) {
        this(peroAgent, handover, streamBus, permissionRegistry, budgetPersistence, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentTaskHandler(PeroAgent peroAgent, Handover handover, TaskStreamBus streamBus,
                            ToolPermissionRegistry permissionRegistry,
                            TaskBudgetPersistencePort budgetPersistence,
                            HumanTaskRepositoryPort humanRepo) {
        this.peroAgent = peroAgent;
        this.handover = handover;
        this.streamBus = streamBus;
        this.permissionRegistry = permissionRegistry;
        this.budgetPersistence = budgetPersistence;
        this.humanRepo = humanRepo;
    }

    @Override
    public String taskType() {
        return "AGENT";
    }

    @Override
    public List<StepDef> planSteps(JsonNode payloadArgs) {
        return List.of(
                StepDef.of(PLAN_STEP_NO, "PLAN", "感知与规划"),
                StepDef.of(GENERATE_STEP_NO, "GENERATE", "生成最终答案"));
    }

    @Override
    public StepResult executeStep(TaskExecutionContext ctx)
            throws RetryableTaskException, FatalTaskException, HumanRequiredException {
        int no = ctx.currentStepNo();
        if (no == PLAN_STEP_NO) {
            return planStep(ctx);
        }
        if (no == GENERATE_STEP_NO) {
            return generateStep(ctx);
        }
        if (no >= NODE_BASE && no < NODE_BASE + MAX_NODES) {
            return nodeStep(ctx, no - NODE_BASE);
        }
        throw new FatalTaskException(ErrorCode.INTERNAL, "未知 Agent 步骤: " + no);
    }

    /** PLAN 步：感知 + 规划，checkpoint 存整份 plan JSON，动态注册 NODE 步。 */
    private StepResult planStep(TaskExecutionContext ctx) {
        String userId = ctx.requireArg("userId").asText();
        String sessionId = ctx.requireArg("sessionId").asText();
        String userInput = ctx.requireArg("userInput").asText();

        Plan plan = peroAgent.runPlan(userId, sessionId, userInput);
        String planJson;
        try {
            planJson = MAPPER.writeValueAsString(plan.steps());
        } catch (Exception e) {
            throw new FatalTaskException(ErrorCode.INTERNAL, "plan 序列化失败: " + e.getMessage(), e);
        }
        ctx.saveCheckpoint(planJson);

        int n = Math.min(plan.steps().size(), MAX_NODES);
        if (plan.steps().size() > MAX_NODES) {
            log.warn("任务 {} 计划节点数 {} 超上限 {}，超出部分不注册", ctx.taskId(), plan.steps().size(), MAX_NODES);
        }
        for (int i = 0; i < n; i++) {
            ctx.registerStep(StepDef.of(NODE_BASE + i, "NODE_" + (i + 1),
                    truncate(plan.steps().get(i).goal())));
        }
        log.info("任务 {} PLAN 完成：{} 个节点", ctx.taskId(), n);
        return new StepResult(false, planJson, 5, null);
    }

    /**
     * NODE_i 步：从 PLAN checkpoint 读回计划，切出第 i 个节点执行单节点 executeLoop。
     * 控制检查：hook.beforeNode（节点开始前）+ 每次 ReAct 迭代顶部；
     * 暂停/取消以 ControlSignalException 穿透，worker 在步骤边界捕获。
     */
    private StepResult nodeStep(TaskExecutionContext ctx, int index) {
        List<PlanStep> steps = loadPlan(ctx);
        if (index >= steps.size()) {
            // plan 收缩（Optimizer 调整）容错：节点已不存在，跳过
            return StepResult.skipped();
        }
        PlanStep target = steps.get(index);
        String userId = ctx.requireArg("userId").asText();
        String sessionId = ctx.requireArg("sessionId").asText();
        String userInput = ctx.requireArg("userInput").asText();

        Perception p = peroAgent.perceive(userId, sessionId, userInput);
        handover.init(userId, sessionId, userInput);
        handover.declarePlan(new Plan(steps));

        peroAgent.executeLoop(p, new Plan(List.of(target)), handover,
                new BusSseSender(streamBus, ctx.taskId(), MAPPER), new ControlHook(),
                governanceOf(ctx, userId, sessionId));

        ObjectNode cp = MAPPER.createObjectNode().put("nodeId", target.id()).put("index", index);
        int percent = 5 + (85 * (index + 1)) / Math.max(1, steps.size());
        return new StepResult(false, cp.toString(), percent, null);
    }

    /** GENERATE 步：生成最终答案并经 bus 推送 delta/done；答案存 checkpoint 供重复提问回放。 */
    private StepResult generateStep(TaskExecutionContext ctx) {
        String userId = ctx.requireArg("userId").asText();
        String sessionId = ctx.requireArg("sessionId").asText();
        String userInput = ctx.requireArg("userInput").asText();

        Perception p = peroAgent.perceive(userId, sessionId, userInput);
        handover.init(userId, sessionId, userInput);
        handover.declarePlan(new Plan(loadPlan(ctx)));

        String answer = peroAgent.generatePhase(p, handover, userId, sessionId,
                new BusSseSender(streamBus, ctx.taskId(), MAPPER));

        ObjectNode cp = MAPPER.createObjectNode().put("answer", answer);
        return new StepResult(false, cp.toString(), 100, null);
    }

    /** 从 PLAN 步 checkpoint 读回整份计划（恢复与同 run 内均走此路径）。 */
    private List<PlanStep> loadPlan(TaskExecutionContext ctx) {
        String planJson = ctx.checkpointOf(PLAN_STEP_NO);
        if (planJson == null || planJson.isBlank()) {
            throw new FatalTaskException(ErrorCode.INTERNAL,
                    "PLAN checkpoint 缺失，无法定位节点计划 taskId=" + ctx.taskId());
        }
        try {
            return MAPPER.readValue(planJson, new TypeReference<List<PlanStep>>() {
            });
        } catch (Exception e) {
            throw new FatalTaskException(ErrorCode.INTERNAL,
                    "PLAN checkpoint 反序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * F1/F2：从 payload.args 构造 ReAct 治理上下文。
     * agentName 取 args.agentName（默认 "pero-agent"）；caller 的 role/scope 取
     * args.userRole / args.userScope（逗号分隔）；预算取 args.budget（F2，缺省系统封顶），
     * 人工提额（humanInputs 中的 iterationLimit/tokenLimit/costLimit）覆盖上限。
     * 无任何治理组件时返回 null（不拦截/不计量，行为与治理前一致）。
     */
    private ReActGovernance governanceOf(TaskExecutionContext ctx, String userId, String sessionId) {
        if (permissionRegistry == null && budgetPersistence == null) {
            return null;
        }
        JsonNode args = ctx.args();
        String agentName = textArg(args, "agentName", "pero-agent");
        String role = textArg(args, "userRole", null);
        List<String> scopes = new java.util.ArrayList<>();
        String scopeCsv = textArg(args, "userScope", null);
        if (scopeCsv != null) {
            for (String s : scopeCsv.split(",")) {
                if (!s.isBlank()) {
                    scopes.add(s.strip());
                }
            }
        }
        return new ReActGovernance(ToolCaller.of(userId, role, scopes), agentName, sessionId,
                budgetTrackerOf(ctx), approvalDecisionsOf(ctx));
    }

    /**
     * F3：从 RESOLVED TOOL_APPROVAL 人工任务读回批准/驳回决策（toolName → approve）。
     * 决策归属键取人工任务 formSchema.toolName（抛出时写入），值取 formValue.approve；
     * 同一工具多单时后处置的覆盖先前的。
     */
    private Map<String, Boolean> approvalDecisionsOf(TaskExecutionContext ctx) {
        if (humanRepo == null) {
            return Map.of();
        }
        Map<String, Boolean> decisions = new HashMap<>();
        try {
            for (var ht : humanRepo.findByTaskId(ctx.taskId())) {
                if (ht.kind() != com.wikiagent.domain.task.HumanTaskKind.TOOL_APPROVAL
                        || ht.status() != com.wikiagent.domain.task.HumanTaskStatus.RESOLVED
                        || ht.formSchema() == null || !ht.formSchema().hasNonNull("toolName")
                        || ht.formValue() == null) {
                    continue;
                }
                decisions.put(ht.formSchema().get("toolName").asText(),
                        ht.formValue().path("approve").asBoolean(false));
            }
        } catch (Exception e) {
            // 决策读取失败不阻断执行：审批门按未决处理（保守抛人工）
            log.warn("任务 {} TOOL_APPROVAL 决策读取失败: {}", ctx.taskId(), e.getMessage());
        }
        return decisions;
    }

    /**
     * F2：预算记账器构造。预算快照从 payload.args.budget 读回（断点恢复不重置）；
     * 人工提额（INPUT 表单中的 iterationLimit/tokenLimit/costLimit）覆盖上限（仍受系统封顶）；
     * sink 把每轮最新预算经 {@link TaskBudgetPersistencePort} 写回 payload.budget。
     */
    private BudgetTracker budgetTrackerOf(TaskExecutionContext ctx) {
        if (budgetPersistence == null) {
            return null;
        }
        JsonNode budgetNode = ctx.args() == null ? null : ctx.args().get("budget");
        TaskBudget budget = TaskBudget.fromJson(budgetNode);
        // 人工提额：预算超限 INPUT 表单字段覆盖上限（worker 已把 formValue 平铺进 humanInputs）
        JsonNode iterOverride = ctx.humanInputs().get("iterationLimit");
        JsonNode tokenOverride = ctx.humanInputs().get("tokenLimit");
        JsonNode costOverride = ctx.humanInputs().get("costLimit");
        if (iterOverride != null || tokenOverride != null || costOverride != null) {
            budget = budget.withLimits(
                    tokenOverride != null && tokenOverride.isNumber() ? tokenOverride.longValue() : null,
                    costOverride != null && costOverride.isNumber() ? costOverride.doubleValue() : null,
                    iterOverride != null && iterOverride.isNumber() ? iterOverride.intValue() : null);
        }
        return new BudgetTracker(budget, b -> {
            // 双写：① 原地更新 ctx.args() 快照（同一 run 内后续 NODE 步读到最新用量）；
            //       ② 经端口落库 payload.budget（断点恢复不重置）
            if (ctx.args() instanceof ObjectNode on) {
                on.set("budget", b.toJson());
            }
            budgetPersistence.saveBudget(ctx.taskId(), b);
        });
    }

    private static String textArg(JsonNode args, String key, String def) {
        JsonNode v = args == null ? null : args.get(key);
        return v != null && v.isTextual() && !v.asText().isBlank() ? v.asText() : def;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 100 ? text : text.substring(0, 100);
    }

    /** 步骤级控制钩子：节点开始前与每次 ReAct 迭代顶部做双读检查（Redis 标志 + DB 权威痕迹）。 */
    private static final class ControlHook implements PeroLoopHook {

        @Override
        public void beforeNode(PlanStep step, int index) {
            TaskControlContext.checkpointAndThrowIfSignaled();
        }

        @Override
        public void afterReactIteration(PlanStep step, int iter) {
            TaskControlContext.checkpointAndThrowIfSignaled();
        }
    }
}
