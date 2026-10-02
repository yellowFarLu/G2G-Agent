package com.wikiagent.domain.task;

/**
 * 人工接管方式（human_task.kind 取值集，规格 2.4）。
 */
public enum HumanTaskKind {
    /** 暂停等人工输入，提交 form_value 后恢复任务。 */
    INPUT,
    /** 人工直接终结剩余步骤，任务转 COMPLETED。 */
    DIRECT_RESOLVE,
    /** 子项目 B：提供加密文档打开口令（formValue 含 decryptPassword），校验后续跑。 */
    DECRYPT,
    /** 子项目 B：低置信抽取结果人工复核。 */
    REVIEW,
    /** 子项目 I：高风险工具调用人工批准。 */
    TOOL_APPROVAL
}
