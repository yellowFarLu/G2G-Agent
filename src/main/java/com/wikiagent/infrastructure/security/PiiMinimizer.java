package com.wikiagent.infrastructure.security;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 子项目 J（AC-J3）：PII 最小化外发处理器（纯逻辑组件，不依赖 Spring/网络）。
 * <p>
 * 在外发 LLM 之前对文本做 PII 识别，支持两类策略（每类可独立配置）：
 * <ul>
 *   <li>{@link Policy#MASK}：掩码替换后文本仍可对话（保留头尾，中间打码）；</li>
 *   <li>{@link Policy#BLOCK}：命中即阻断，文本不得外发（由上层装饰器转成固定拒答）。</li>
 * </ul>
 * 覆盖类别：手机号 / 身份证号 / 银行卡号 / 邮箱。匹配区间按优先级去重
 * （身份证 18 位优先于 16-19 位银行卡，手机号 11 位优先于银行卡），
 * 避免同一数字串被重复掩码。
 */
public class PiiMinimizer {

    /** PII 类别（配置 key 与 {@link #fromConfigKey(String)} 对应）。 */
    public enum PiiCategory {
        PHONE("phone"),
        ID_CARD("id-card"),
        BANK_CARD("bank-card"),
        EMAIL("email");

        private final String configKey;

        PiiCategory(String configKey) {
            this.configKey = configKey;
        }

        public String configKey() {
            return configKey;
        }

        static PiiCategory fromConfigKey(String key) {
            for (PiiCategory c : values()) {
                if (c.configKey.equalsIgnoreCase(key)) {
                    return c;
                }
            }
            return null;
        }
    }

    /** 处置策略。 */
    public enum Policy {
        MASK,
        BLOCK;

        static Policy parse(String raw, Policy fallback) {
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            return "block".equalsIgnoreCase(raw.trim()) ? BLOCK : MASK;
        }
    }

    /**
     * @param category 命中类别
     * @param start    原文起始偏移
     * @param end      原文结束偏移
     * @param masked   掩码后片段（BLOCK 时仍给出掩码，便于审计展示）
     */
    public record Finding(PiiCategory category, int start, int end, String masked) {
    }

    /**
     * @param blocked     是否阻断
     * @param text        MASK 时为脱敏后文本（可外发）；BLOCK 时为原文（调用方不得外发）
     * @param findings    命中明细（不含完整原文，仅类别/偏移/掩码片段）
     * @param blockReason 阻断原因（仅 blocked=true 时非空）
     */
    public record Result(boolean blocked, String text, List<Finding> findings, String blockReason) {
        static Result masked(String text, List<Finding> findings) {
            return new Result(false, text, List.copyOf(findings), null);
        }

        static Result blocked(String original, List<Finding> findings, String reason) {
            return new Result(true, original, List.copyOf(findings), reason);
        }
    }

    // 数字边界使用前后环视，避免从更长数字串中截取出"伪手机号/伪身份证"
    private static final Pattern PHONE = Pattern.compile("(?<![0-9])1[3-9]\\d{9}(?![0-9])");
    private static final Pattern ID_CARD = Pattern.compile("(?<![0-9Xx])\\d{17}[0-9Xx](?![0-9Xx])");
    private static final Pattern BANK_CARD = Pattern.compile("(?<![0-9])\\d{16,19}(?![0-9])");
    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /** 扫描优先级（高在前，区间重叠时高优先级拿走）：身份证 → 手机号 → 银行卡；邮箱独立。 */
    private static final List<Map.Entry<PiiCategory, Pattern>> SCAN_ORDER = List.of(
            Map.entry(PiiCategory.ID_CARD, ID_CARD),
            Map.entry(PiiCategory.PHONE, PHONE),
            Map.entry(PiiCategory.BANK_CARD, BANK_CARD),
            Map.entry(PiiCategory.EMAIL, EMAIL));

    private final Map<PiiCategory, Policy> policies;

    public PiiMinimizer(Map<PiiCategory, Policy> policies) {
        Map<PiiCategory, Policy> copy = new HashMap<>();
        if (policies != null) {
            copy.putAll(policies);
        }
        this.policies = Map.copyOf(copy);
    }

    /** 全部类别默认 MASK。 */
    public static PiiMinimizer maskAll() {
        return new PiiMinimizer(Map.of());
    }

    /** 从 properties 风格配置（key=phone/id-card/bank-card/email，value=mask|block）构造。 */
    public static PiiMinimizer fromConfig(Map<String, String> policyConfig) {
        Map<PiiCategory, Policy> map = new HashMap<>();
        if (policyConfig != null) {
            policyConfig.forEach((k, v) -> {
                PiiCategory category = PiiCategory.fromConfigKey(k);
                if (category != null) {
                    map.put(category, Policy.parse(v, Policy.MASK));
                }
            });
        }
        return new PiiMinimizer(map);
    }

    public Policy policyOf(PiiCategory category) {
        return policies.getOrDefault(category, Policy.MASK);
    }

    /**
     * 最小化处理。
     *
     * @param text 待外发文本（null/空原样返回，无命中）
     */
    public Result minimize(String text) {
        if (text == null || text.isEmpty()) {
            return new Result(false, text, List.of(), null);
        }

        // 1) 按优先级扫描，区间互斥
        List<int[]> occupied = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        List<Finding> blockFindings = new ArrayList<>();
        StringBuilder blockReasons = new StringBuilder();

        for (Map.Entry<PiiCategory, Pattern> entry : SCAN_ORDER) {
            PiiCategory category = entry.getKey();
            Matcher m = entry.getValue().matcher(text);
            while (m.find()) {
                int start = m.start();
                int end = m.end();
                if (overlaps(occupied, start, end)) {
                    continue;
                }
                occupied.add(new int[]{start, end});
                String raw = text.substring(start, end);
                Finding finding = new Finding(category, start, end, maskOf(category, raw));
                findings.add(finding);
                if (policyOf(category) == Policy.BLOCK) {
                    blockFindings.add(finding);
                    if (!blockReasons.isEmpty()) {
                        blockReasons.append(", ");
                    }
                    blockReasons.append(category.name());
                }
            }
        }

        if (!blockFindings.isEmpty()) {
            return Result.blocked(text, blockFindings,
                    "外发文本命中阻断策略的 PII 类别: " + blockReasons);
        }
        if (findings.isEmpty()) {
            return new Result(false, text, List.of(), null);
        }

        // 2) 从后向前替换为掩码（偏移不失效）
        StringBuilder sb = new StringBuilder(text);
        findings.stream()
                .sorted(Comparator.comparingInt(Finding::start).reversed())
                .forEach(f -> sb.replace(f.start(), f.end(), f.masked()));
        return Result.masked(sb.toString(), findings);
    }

    private static boolean overlaps(List<int[]> occupied, int start, int end) {
        for (int[] range : occupied) {
            if (start < range[1] && end > range[0]) {
                return true;
            }
        }
        return false;
    }

    /** 类别专属掩码：数字类保留头几位与后 4 位；邮箱保留 local 前 2 字符 + 域名。 */
    static String maskOf(PiiCategory category, String raw) {
        return switch (category) {
            case PHONE -> keep(raw, 3, 4, '*', 4);
            case ID_CARD -> keep(raw, 6, 4, '*', 8);
            case BANK_CARD -> keep(raw, 4, 4, '*', 8);
            case EMAIL -> maskEmail(raw);
        };
    }

    private static String keep(String raw, int prefix, int suffix, char mask, int maskCount) {
        if (raw.length() <= prefix + suffix) {
            // 异常短串：整体打码，不保留任何原文
            return "*".repeat(Math.max(maskCount, raw.length()));
        }
        return raw.substring(0, prefix) + String.valueOf(mask).repeat(maskCount)
                + raw.substring(raw.length() - suffix);
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 2) {
            return "*".repeat(Math.max(3, at <= 0 ? 3 : at)) + (at < 0 ? "" : email.substring(at));
        }
        return email.substring(0, 2) + "***" + email.substring(at);
    }
}
