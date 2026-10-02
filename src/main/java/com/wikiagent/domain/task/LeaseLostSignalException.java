package com.wikiagent.domain.task;

/**
 * 租约易主信号：心跳续约或步骤边界发现 lease_owner 已不属于当前 worker
 * （任务被崩溃恢复/看门狗回收并被他副本领取）时抛出。
 * Worker 捕获后安静让位——不写任何任务状态（任务已归新 owner）。
 */
public class LeaseLostSignalException extends ControlSignalException {

    public LeaseLostSignalException(String message) {
        super(message);
    }
}
