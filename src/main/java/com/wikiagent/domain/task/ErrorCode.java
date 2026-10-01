package com.wikiagent.domain.task;

/**
 * 失败分类错误码（task_instance.error_code 取值集，规格 3.3）。
 * 可重试：TIMEOUT / THIRD_PARTY_5XX / PARSE_FAILED / INTERNAL；
 * 不重试即 FAILED：THIRD_PARTY_4XX / VALIDATION_FAILED / BUDGET_EXCEEDED。
 */
public enum ErrorCode {
    TIMEOUT,
    THIRD_PARTY_5XX,
    THIRD_PARTY_4XX,
    PARSE_FAILED,
    VALIDATION_FAILED,
    BUDGET_EXCEEDED,
    INTERNAL;

    /** 是否可重试（对应规格 3.3 分类）。 */
    public boolean retryable() {
        return this == TIMEOUT || this == THIRD_PARTY_5XX || this == PARSE_FAILED || this == INTERNAL;
    }
}
