package com.wikiagent.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-J3 {@link PiiMinimizer} 纯逻辑单测：
 * 四类 PII 每类 ≥2 样例的 MASK 形态、BLOCK 策略、可对话性与边界（无 PII 原样返回、
 * 长数字串不被误截为手机号）。
 */
class PiiMinimizerTest {

    static Stream<Arguments> maskSamples() {
        return Stream.of(
                // 手机号：前 3 后 4
                Arguments.of(PiiMinimizer.PiiCategory.PHONE, "13800138000", "138****8000"),
                Arguments.of(PiiMinimizer.PiiCategory.PHONE, "15912345678", "159****5678"),
                // 身份证：前 6 后 4（末位 X 保留）
                Arguments.of(PiiMinimizer.PiiCategory.ID_CARD, "11010119900307123X", "110101********123X"),
                Arguments.of(PiiMinimizer.PiiCategory.ID_CARD, "320102198507154321", "320102********4321"),
                // 银行卡：前 4 后 4（16 位/19 位）
                Arguments.of(PiiMinimizer.PiiCategory.BANK_CARD, "6228480402564890", "6228********4890"),
                Arguments.of(PiiMinimizer.PiiCategory.BANK_CARD, "6217001234567890123", "6217********0123"),
                // 邮箱：local 保留前 2；极短 local 整体打码
                Arguments.of(PiiMinimizer.PiiCategory.EMAIL, "zhang.san@example.com", "zh***@example.com"),
                Arguments.of(PiiMinimizer.PiiCategory.EMAIL, "a@b.co", "***@b.co"));
    }

    @ParameterizedTest
    @MethodSource("maskSamples")
    void mask策略下各类pii按类别规则脱敏且不残留原文(PiiMinimizer.PiiCategory category,
                                                 String raw, String expectedMask) {
        PiiMinimizer.Result result = PiiMinimizer.maskAll().minimize(raw);

        assertThat(result.blocked()).isFalse();
        assertThat(result.text()).isEqualTo(expectedMask);
        assertThat(result.text()).doesNotContain(raw);
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).category()).isEqualTo(category);
    }

    @Test
    void mask后文本仍可对话且周围中文保留() {
        PiiMinimizer.Result result = PiiMinimizer.maskAll()
                .minimize("我的手机13800138000有问题，请尽快回电这个号码");

        assertThat(result.blocked()).isFalse();
        assertThat(result.text())
                .isEqualTo("我的手机138****8000有问题，请尽快回电这个号码")
                .doesNotContain("13800138000");
    }

    @Test
    void 一句中多类pii全部脱敏() {
        String text = "联系人 13800138000 邮箱 zhang.san@example.com 卡号 6228480402564890";

        PiiMinimizer.Result result = PiiMinimizer.maskAll().minimize(text);

        assertThat(result.blocked()).isFalse();
        assertThat(result.text())
                .contains("138****8000", "zh***@example.com", "6228********4890")
                .doesNotContain("13800138000", "zhang.san", "6228480402564890");
        assertThat(result.findings()).hasSize(3);
    }

    @Test
    void block策略命中即阻断且原文保留在结果中但标记不可外发() {
        PiiMinimizer minimizer = new PiiMinimizer(
                Map.of(PiiMinimizer.PiiCategory.PHONE, PiiMinimizer.Policy.BLOCK));

        PiiMinimizer.Result result = minimizer.minimize("打我电话 13800138000");

        assertThat(result.blocked()).isTrue();
        assertThat(result.text()).isEqualTo("打我电话 13800138000"); // 调用方必须依据 blocked 拒绝外发
        assertThat(result.blockReason()).contains("PHONE");
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).category()).isEqualTo(PiiMinimizer.PiiCategory.PHONE);
    }

    @Test
    void block策略未命中的其它pii按mask处理() {
        PiiMinimizer minimizer = new PiiMinimizer(
                Map.of(PiiMinimizer.PiiCategory.PHONE, PiiMinimizer.Policy.BLOCK));

        PiiMinimizer.Result result = minimizer.minimize("邮箱 zhang.san@example.com");

        assertThat(result.blocked()).isFalse();
        assertThat(result.text()).contains("zh***@example.com");
    }

    @Test
    void 无pii文本原样返回且无命中() {
        String text = "PMS 系统的退货审批节点有哪些？";

        PiiMinimizer.Result result = PiiMinimizer.maskAll().minimize(text);

        assertThat(result.blocked()).isFalse();
        assertThat(result.text()).isEqualTo(text);
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void 长数字串不会被截取出伪手机号() {
        // 20 位业务单号：内含 1[3-9] 形态子串，但前后都是数字，手机号/银行卡边界环视必须拒绝
        String text = "单号 99138001380001234567";

        PiiMinimizer.Result result = PiiMinimizer.maskAll().minimize(text);

        assertThat(result.findings()).isEmpty();
        assertThat(result.text()).isEqualTo(text);
    }

    @Test
    void 身份证优先于银行卡匹配不会重复掩码() {
        PiiMinimizer.Result result = PiiMinimizer.maskAll().minimize("身份证 11010119900307123X");

        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).category()).isEqualTo(PiiMinimizer.PiiCategory.ID_CARD);
        assertThat(result.text()).isEqualTo("身份证 110101********123X");
    }

    @Test
    void fromConfig支持block字符串() {
        PiiMinimizer minimizer = PiiMinimizer.fromConfig(Map.of(
                "phone", "block",
                "email", "MASK",
                "unknown-key", "block")); // 未知键忽略，不报错

        assertThat(minimizer.policyOf(PiiMinimizer.PiiCategory.PHONE))
                .isEqualTo(PiiMinimizer.Policy.BLOCK);
        assertThat(minimizer.policyOf(PiiMinimizer.PiiCategory.EMAIL))
                .isEqualTo(PiiMinimizer.Policy.MASK);
        assertThat(minimizer.policyOf(PiiMinimizer.PiiCategory.ID_CARD))
                .isEqualTo(PiiMinimizer.Policy.MASK); // 缺省 mask
    }
}
