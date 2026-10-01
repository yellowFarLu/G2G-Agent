package com.wikiagent.domain.task;

/**
 * 可重试任务异常：worker 按 ErrorClassifier + Backoff 重新入队。
 */
public class RetryableTaskException extends RuntimeException {

    private final ErrorCode errorCode;

    public RetryableTaskException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public RetryableTaskException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
