package com.wikiagent.infrastructure.lock;

/**
 * v6 §22.5 锁繁忙异常（获取分布式锁失败时抛出）。
 * <p>
 * 调用方可捕获后选择重试、降级或返回 503。
 */
public class LockBusyException extends RuntimeException {

    private final String key;

    public LockBusyException(String key) {
        super("锁繁忙: " + key);
        this.key = key;
    }

    public LockBusyException(String key, Throwable cause) {
        super("锁繁忙: " + key, cause);
        this.key = key;
    }

    public String key() {
        return key;
    }
}
