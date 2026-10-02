package com.wikiagent.domain.task;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 步骤执行上下文：worker 构造，传给 TaskHandler.executeStep。
 * AGENT 任务在 PLAN 步内通过 registerStep 动态注册后续节点步骤（Task 12）。
 */
public final class TaskExecutionContext {

    private final String taskId;
    private final String taskType;
    private final JsonNode args;
    private final Map<String, JsonNode> humanInputs;
    private final int attempt;
    private final List<StepDef> registeredSteps = new ArrayList<>();
    private final Map<Integer, String> checkpoints = new HashMap<>();

    public TaskExecutionContext(String taskId, String taskType, JsonNode args,
                                Map<String, JsonNode> humanInputs, int attempt) {
        this.taskId = taskId;
        this.taskType = taskType;
        this.args = args;
        this.humanInputs = humanInputs == null ? Map.of() : Map.copyOf(humanInputs);
        this.attempt = attempt;
    }

    public String taskId() {
        return taskId;
    }

    public String taskType() {
        return taskType;
    }

    public JsonNode args() {
        return args;
    }

    public Map<String, JsonNode> humanInputs() {
        return humanInputs;
    }

    public int attempt() {
        return attempt;
    }

    /** 动态注册步骤（保持插入序，重复 no 幂等覆盖）。 */
    public void registerStep(StepDef def) {
        registeredSteps.removeIf(existing -> existing.no() == def.no());
        registeredSteps.add(def);
        registeredSteps.sort(java.util.Comparator.comparingInt(StepDef::no));
    }

    /** 已注册步骤（只读、按 no 升序）；worker 每步后用它做 saveAllIfAbsent。 */
    public List<StepDef> registeredSteps() {
        return Collections.unmodifiableList(registeredSteps);
    }

    /** 预注册已存在步骤（断点续跑时 worker 回填，checkpointOf 可见）。 */
    public void seedStep(StepDef def, String checkpointJson) {
        registerStep(def);
        if (checkpointJson != null) {
            checkpoints.put(def.no(), checkpointJson);
        }
    }

    public void saveCheckpoint(String json) {
        // 以当前正在执行的步骤为准：取注册序中最后一个 RUNNING 场景由 worker 维护当前步；
        // handler 侧约定 saveCheckpoint 作用于"当前步"，由 worker 在调用前设置 currentStepNo。
        if (currentStepNo == null) {
            throw new IllegalStateException("saveCheckpoint 必须在 worker 设置当前步骤后调用, taskId=" + taskId);
        }
        checkpoints.put(currentStepNo, json);
    }

    private Integer currentStepNo;

    public void setCurrentStepNo(int stepNo) {
        this.currentStepNo = stepNo;
    }

    /** 当前正在执行的步骤号（worker 设置；未设置返回 null）。 */
    public Integer currentStepNo() {
        return currentStepNo;
    }

    public String checkpointOf(int stepNo) {
        return checkpoints.get(stepNo);
    }

    public JsonNode requireArg(String key) {
        if (args == null || !args.hasNonNull(key)) {
            throw new IllegalArgumentException("缺少必填参数: " + key + ", taskId=" + taskId);
        }
        return args.get(key);
    }
}
