package com.wikiagent.domain.identity;

/**
 * v3 §6.5 知识库 9 个垂直领域标签。
 * <p>
 * 每个领域内部再划分为 6 个子领域（{@link SubDomainTag}）。
 * 9 × 6 = 54 个 (domain, sub_domain) 组合。
 */
public enum DomainTag {
    INDUSTRY_SOLUTION("industry_solution", "行业解决方案"),
    MERCHANT_CENTER("merchant_center", "商家中心"),
    PMS("pms", "PMS"),
    SERVICE_PROVIDER("service_provider", "服务商"),
    TRUNK_LINE("trunk_line", "干线"),
    CUSTOMS("customs", "关务"),
    SETTLEMENT("settlement", "结算"),
    FIRST_MILE("first_mile", "首公里"),
    TRAJECTORY("trajectory", "轨迹");

    private final String code;
    private final String label;

    DomainTag(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() { return code; }
    public String label() { return label; }

    public static DomainTag fromCode(String code) {
        if (code == null) return null;
        for (DomainTag d : values()) {
            if (d.code.equalsIgnoreCase(code)) return d;
        }
        return null;
    }

    public static String[] allCodes() {
        DomainTag[] tags = values();
        String[] codes = new String[tags.length];
        for (int i = 0; i < tags.length; i++) {
            codes[i] = tags[i].code;
        }
        return codes;
    }
}
