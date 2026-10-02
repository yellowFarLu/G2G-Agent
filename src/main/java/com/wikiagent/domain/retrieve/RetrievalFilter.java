package com.wikiagent.domain.retrieve;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 检索权限过滤表达式（domain 层纯 Java）。
 * <p>
 * 支持语法（仅 AND 合取）：
 * <ul>
 *   <li>{@code domain='industry'} — 等值</li>
 *   <li>{@code identity IN ('admin','product')} — 集合包含</li>
 *   <li>{@code subDomain='faq' AND identity IN ('admin')} — 合取</li>
 * </ul>
 * 字段映射：domain→domain_tag，subDomain→sub_domain_tag，identity→required_identity
 * （knowledge_metadata 列名 / Milvus 标量字段名）。
 * 解析失败的片段被丢弃（保守放行其余条件），全空表达式表示不过滤。
 */
public final class RetrievalFilter {

    private enum Op {EQ, IN}

    private record Condition(String field, Op op, Set<String> values) {
    }

    private final List<Condition> conditions;

    private RetrievalFilter(List<Condition> conditions) {
        this.conditions = conditions;
    }

    public static RetrievalFilter none() {
        return new RetrievalFilter(List.of());
    }

    /** 解析表达式；null/空/全部解析失败 → 空过滤器（不过滤）。 */
    public static RetrievalFilter parse(String expression) {
        if (expression == null || expression.isBlank()) {
            return none();
        }
        List<Condition> out = new ArrayList<>();
        for (String part : expression.split("(?i)\\s+AND\\s+")) {
            Condition c = parseCondition(part.trim());
            if (c != null) {
                out.add(c);
            }
        }
        return new RetrievalFilter(out);
    }

    private static Condition parseCondition(String part) {
        if (part.isEmpty()) {
            return null;
        }
        // field IN ('a','b')
        int inIdx = indexOfIgnoreCase(part, " IN ");
        if (inIdx > 0) {
            String field = normalizeField(part.substring(0, inIdx).trim());
            Set<String> values = parseValues(part.substring(inIdx + 4).trim());
            return field == null || values.isEmpty() ? null : new Condition(field, Op.IN, values);
        }
        // field = 'v' / field == 'v'
        int eqIdx = part.indexOf("==");
        int len = 2;
        if (eqIdx < 0) {
            eqIdx = part.indexOf('=');
            len = 1;
        }
        if (eqIdx > 0) {
            String field = normalizeField(part.substring(0, eqIdx).trim());
            Set<String> values = parseValues(part.substring(eqIdx + len).trim());
            return field == null || values.isEmpty() ? null : new Condition(field, Op.EQ, values);
        }
        return null;
    }

    /** 解析值列表：支持 ('a','b') / 'a' / 'a','b'。 */
    private static Set<String> parseValues(String raw) {
        String s = raw.trim();
        if (s.startsWith("(") && s.endsWith(")")) {
            s = s.substring(1, s.length() - 1);
        }
        Set<String> out = new LinkedHashSet<>();
        for (String v : s.split(",")) {
            v = v.trim();
            if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) {
                v = v.substring(1, v.length() - 1);
            }
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                v = v.substring(1, v.length() - 1);
            }
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    private static String normalizeField(String field) {
        return switch (field) {
            case "domain", "domain_tag" -> "domain_tag";
            case "subDomain", "sub_domain_tag" -> "sub_domain_tag";
            case "identity", "required_identity" -> "required_identity";
            default -> null; // 未知字段丢弃该条件
        };
    }

    public boolean isEmpty() {
        return conditions.isEmpty();
    }

    /** 合并两个过滤器（AND）。 */
    public RetrievalFilter and(RetrievalFilter other) {
        if (other == null || other.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return other;
        }
        List<Condition> merged = new ArrayList<>(conditions);
        merged.addAll(other.conditions);
        return new RetrievalFilter(merged);
    }

    /**
     * 关系库侧匹配：元数据行是否满足过滤。
     * 元数据缺失（三值全 null）视为未打标公共知识，默认放行（向后兼容）。
     */
    public boolean matches(String domainTag, String subDomainTag, String requiredIdentity) {
        if (isEmpty()) {
            return true;
        }
        if (domainTag == null && subDomainTag == null && requiredIdentity == null) {
            return true;
        }
        for (Condition c : conditions) {
            String actual = switch (c.field()) {
                case "domain_tag" -> domainTag;
                case "sub_domain_tag" -> subDomainTag;
                case "required_identity" -> requiredIdentity;
                default -> null;
            };
            if (actual == null || !c.values().contains(actual)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 转 Milvus filter 表达式（标量字段：domain_tag / sub_domain_tag / required_identity）。
     * 空过滤器返回 null。语法：field == "v" / field in ["a","b"]，AND 用 and 连接。
     */
    public String toMilvusExpr() {
        if (isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (Condition c : conditions) {
            if (c.op() == Op.EQ) {
                parts.add(c.field() + " == \"" + escape(c.values().iterator().next()) + "\"");
            } else {
                List<String> vs = new ArrayList<>();
                for (String v : c.values()) {
                    vs.add("\"" + escape(v) + "\"");
                }
                parts.add(c.field() + " in [" + String.join(",", vs) + "]");
            }
        }
        return String.join(" and ", parts);
    }

    private static String escape(String v) {
        return v.replace("\\", "").replace("\"", "");
    }

    /** 不依赖外部库的 case-insensitive indexOf。 */
    private static int indexOfIgnoreCase(String s, String target) {
        if (s == null || target == null) {
            return -1;
        }
        int max = s.length() - target.length();
        for (int i = 0; i <= max; i++) {
            if (s.regionMatches(true, i, target, 0, target.length())) {
                return i;
            }
        }
        return -1;
    }
}
