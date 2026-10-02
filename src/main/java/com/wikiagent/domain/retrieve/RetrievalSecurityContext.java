package com.wikiagent.domain.retrieve;

/**
 * 检索安全上下文（domain 层纯 Java）：当前请求的业务身份 ThreadLocal。
 * 由 servlet 过滤器（X-Business-Identity header）写入，请求结束清理。
 */
public final class RetrievalSecurityContext {

    private static final ThreadLocal<String> IDENTITY = new ThreadLocal<>();

    private RetrievalSecurityContext() {
    }

    public static void setIdentity(String identity) {
        IDENTITY.set(identity);
    }

    /** 当前身份；未设置返回 null（不过滤）。 */
    public static String currentIdentity() {
        return IDENTITY.get();
    }

    public static void clear() {
        IDENTITY.remove();
    }

    /** 由当前身份构造身份过滤（identity IN (...)）；无身份 → 空过滤器。 */
    public static RetrievalFilter identityFilter() {
        String id = currentIdentity();
        if (id == null || id.isBlank()) {
            return RetrievalFilter.none();
        }
        return RetrievalFilter.parse("identity IN ('" + id.replace("'", "") + "')");
    }
}
