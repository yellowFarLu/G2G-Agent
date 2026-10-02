package com.wikiagent.application.task;

import com.wikiagent.domain.task.CancelSignalException;
import com.wikiagent.domain.task.ControlFlag;
import com.wikiagent.domain.task.LeaseLostSignalException;
import com.wikiagent.domain.task.PauseSignalException;
import com.wikiagent.domain.task.TaskInstance;
import com.wikiagent.domain.task.TaskStatus;
import com.wikiagent.domain.task.ports.ControlFlagPort;
import com.wikiagent.domain.task.ports.TaskRepositoryPort;

/**
 * 步骤边界控制检查上下文（规格 3.1 ⑤）：worker 在主线程与步骤执行线程各 set 一次，
 * handler 在 ReAct 迭代边界 / 步骤边界调用 {@link #checkpointAndThrowIfSignaled()}。
 * 双读：Redis 即时标志优先，MySQL 权威痕迹（CANCELING / suspendReason）兜底复核。
 * 附加租约校验：expectedOwner 非空时，DB 记录租约易主或状态不再是 RUNNING →
 * 抛 {@link LeaseLostSignalException} 让位（任务已被回收重投，由新 owner 接管）。
 */
public final class TaskControlContext {

    /**
     * @param taskId        当前任务
     * @param flags         控制标志端口（Redis 或 JVM 降级）
     * @param repo          任务仓储（DB 权威复核）
     * @param expectedOwner 本 worker 的 workerId；null 表示不做租约校验
     */
    public record Ctx(String taskId, ControlFlagPort flags, TaskRepositoryPort repo, String expectedOwner) {
    }

    private static final ThreadLocal<Ctx> CTX = new ThreadLocal<>();

    public static void set(Ctx ctx) {
        CTX.set(ctx);
    }

    public static Ctx get() {
        return CTX.get();
    }

    public static void clear() {
        CTX.remove();
    }

    /**
     * 检查点：收到取消抛 {@link CancelSignalException}，收到暂停抛 {@link PauseSignalException}；
     * 任一来源（Redis 标志 / DB 权威痕迹）生效即抛出。
     */
    public static void checkpointAndThrowIfSignaled() {
        Ctx ctx = CTX.get();
        if (ctx == null) {
            return;
        }
        ControlFlag flag = ctx.flags().read(ctx.taskId());
        if (flag == ControlFlag.CANCEL) {
            throw new CancelSignalException();
        }
        if (flag == ControlFlag.PAUSE) {
            throw new PauseSignalException();
        }
        // DB 权威复核：Redis 标志丢失（降级/驱逐）时控制指令仍生效
        TaskInstance task = ctx.repo().findByTaskId(ctx.taskId()).orElse(null);
        if (task == null) {
            return;
        }
        if (task.status() == TaskStatus.CANCELING) {
            throw new CancelSignalException();
        }
        if (task.suspendReason() != null) {
            throw new PauseSignalException();
        }
        // 租约校验：易主或已被回收（非 RUNNING）→ 让位，不得再执行/写状态
        if (ctx.expectedOwner() != null
                && (task.status() != TaskStatus.RUNNING || !ctx.expectedOwner().equals(task.leaseOwner()))) {
            throw new LeaseLostSignalException(
                    "租约易主或任务已回收: taskId=" + ctx.taskId() + " owner=" + task.leaseOwner());
        }
    }
}
