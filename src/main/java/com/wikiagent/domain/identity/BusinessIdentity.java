package com.wikiagent.domain.identity;

/**
 * v3 §6.5 用户业务身份枚举（5 类）。
 * <p>
 * 管理员为用户配置业务身份，根据身份控制知识检索权限。
 */
public enum BusinessIdentity {
    ADMIN("admin", "管理员"),
    BUSINESS("business", "业务"),
    PRODUCT("product", "产品"),
    TECH("tech", "技术"),
    TESTING("testing", "测试");

    private final String code;
    private final String label;

    BusinessIdentity(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() { return code; }
    public String label() { return label; }

    public static BusinessIdentity fromCode(String code) {
        if (code == null) return BUSINESS; // 默认最严
        for (BusinessIdentity i : values()) {
            if (i.code.equalsIgnoreCase(code)) return i;
        }
        return BUSINESS;
    }
}
