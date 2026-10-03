package com.wikiagent.domain.task;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 任务处理器 SPI：每种任务类型一个实现（INGEST/AGENT...），由 TaskHandlerRegistry 收集。
 */
public interface TaskHandler {

    /** 任务类型标识，与 MQ Tag / task_instance.task_type 一致。 */
    String taskType();

    /** 静态步骤规划；AGENT 类动态任务可只返回首步，后续经 ctx.registerStep 注册。 */
    List<StepDef> planSteps(JsonNode payloadArgs);

    /**
     * 执行"当前步"。worker 设置 ctx.currentStep 后调用；控制信号（暂停/取消）以
     * ControlSignalException 抛出并必须穿透 handler 的通用 catch。
     */
    StepResult executeStep(TaskExecutionContext ctx)
            throws RetryableTaskException, FatalTaskException, HumanRequiredException;

    /** 任务被取消时的副作用清理钩子（默认空）。 */
    default void onCancel(TaskExecutionContext ctx) {
    }

    /**
     * 任务最终失败（重试耗尽或 Fatal）时的副作用清理/回滚钩子。
     * 例如 INGEST 任务失败时应把 KbDocument 状态回滚为 FAILED，避免前端列表长期卡住。
     */
    default void onFailed(TaskExecutionContext ctx, ErrorCode code, String msg) {
    }
}
