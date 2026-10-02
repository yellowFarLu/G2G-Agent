package com.wikiagent.domain.observability.trace;

import java.util.UUID;

/**
 * 子项目 I（AC-I1）traceId 生成器（domain 层，纯 Java，无框架依赖）。
 * <p>
 * 入口请求未携带 {@code X-Trace-Id} 头（或头为空白）时生成新 traceId，格式：
 * {@code tr-{nanoTime}-{8 位 UUID}}。nanoTime 保证同进程毫秒内可区分，UUID 段保证跨进程唯一。
 * <p>
 * 任务无请求上下文（恢复扫描 / retry 重投 / 看门狗）时同样使用本生成器生成新 traceId。
 */
public final class TraceIdGenerator {

    /** traceId 统一前缀，便于日志与库表行辨识。 */
    public static final String PREFIX = "tr-";

    private TraceIdGenerator() {
    }

    /** 生成一个新的 traceId。 */
    public static String generate() {
        return PREFIX + System.nanoTime()
                + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /**
     * 解析入口头值：非空白则原样透传（外部网关注入的 traceId 优先，保证全链路同源），
     * 否则生成新 traceId。
     */
    public static String resolve(String headerValue) {
        if (headerValue != null && !headerValue.isBlank()) {
            return headerValue.trim();
        }
        return generate();
    }
}
