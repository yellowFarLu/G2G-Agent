package com.wikiagent.domain.task;

/**
 * 步骤定义：handler 在 planSteps / 动态注册时给出。
 */
public record StepDef(int no, String type, String name, boolean humanCheckpoint, Integer timeoutSec) {

    public static StepDef of(int no, String type, String name) {
        return new StepDef(no, type, name, false, null);
    }

    public StepDef withHumanCheckpoint(boolean humanCheckpoint) {
        return new StepDef(no, type, name, humanCheckpoint, timeoutSec);
    }

    public StepDef withTimeoutSec(Integer timeoutSec) {
        return new StepDef(no, type, name, humanCheckpoint, timeoutSec);
    }
}
