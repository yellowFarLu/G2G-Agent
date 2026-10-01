package com.wikiagent.domain.task;

/**
 * 致命任务异常：不重试，直接 FAILED（error_code 随异常携带）。
 */
public class FatalTaskException extends RuntimeException {

    private final ErrorCode errorCode;

    public FatalTaskException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public FatalTaskException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
