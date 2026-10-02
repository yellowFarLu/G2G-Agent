package com.wikiagent.domain.parse;

/**
 * 加密文档：无口令或口令错误。handler 捕获后建 DECRYPT 人工任务，
 * 带口令续跑；{@code passwordRejected}=true 表示已尝试过口令但被拒绝（累计 3 次转致命）。
 */
public class EncryptedDocumentException extends RuntimeException {

    private final boolean passwordRejected;

    public EncryptedDocumentException(String message, boolean passwordRejected) {
        super(message);
        this.passwordRejected = passwordRejected;
    }

    public EncryptedDocumentException(String message, boolean passwordRejected, Throwable cause) {
        super(message, cause);
        this.passwordRejected = passwordRejected;
    }

    public boolean passwordRejected() {
        return passwordRejected;
    }
}
