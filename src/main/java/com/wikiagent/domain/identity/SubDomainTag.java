package com.wikiagent.domain.identity;

import java.util.Set;

/**
 * v3 §6.5 知识库 6 个子领域标签（按知识类型划分）。
 * <p>
 * 每个领域内部均含 6 个子领域，用于知识分类与身份权限控制。
 */
public enum SubDomainTag {
    BUSINESS("business", "业务知识"),
    PRODUCT("product", "产品知识"),
    TECH("tech", "技术知识"),
    TESTING("testing", "测试知识"),
    SAFETY("safety", "安全生产知识"),
    MANAGEMENT("management", "管理层知识");

    private final String code;
    private final String label;

    SubDomainTag(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() { return code; }
    public String label() { return label; }

    public static SubDomainTag fromCode(String code) {
        if (code == null) return null;
        for (SubDomainTag s : values()) {
            if (s.code.equalsIgnoreCase(code)) return s;
        }
        return null;
    }

    /**
     * v3 §6.5.3 默认身份权限矩阵：返回指定身份可访问的子领域编码集合。
     * <ul>
     *   <li>admin: 全部 6 个</li>
     *   <li>business: business, product</li>
     *   <li>product: business, product, management</li>
     *   <li>tech: business, product, tech, safety</li>
     *   <li>testing: business, product, testing</li>
     * </ul>
     */
    public static Set<String> allowedForIdentity(BusinessIdentity identity) {
        return switch (identity) {
            case ADMIN -> Set.of(BUSINESS.code, PRODUCT.code, TECH.code, TESTING.code, SAFETY.code, MANAGEMENT.code);
            case BUSINESS -> Set.of(BUSINESS.code, PRODUCT.code);
            case PRODUCT -> Set.of(BUSINESS.code, PRODUCT.code, MANAGEMENT.code);
            case TECH -> Set.of(BUSINESS.code, PRODUCT.code, TECH.code, SAFETY.code);
            case TESTING -> Set.of(BUSINESS.code, PRODUCT.code, TESTING.code);
        };
    }
}
