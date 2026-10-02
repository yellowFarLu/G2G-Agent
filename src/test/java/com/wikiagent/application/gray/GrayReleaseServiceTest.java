package com.wikiagent.application.gray;

import com.wikiagent.config.GrayReleaseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GrayReleaseService 灰度决策测试：
 * 未配置不门控、percent 边界、名单优先级、分桶稳定性与分布均匀性、匿名桶、reason 暴露。
 */
class GrayReleaseServiceTest {

    private GrayReleaseProperties props;
    private GrayReleaseService service;

    @BeforeEach
    void setUp() {
        props = new GrayReleaseProperties();
        // 无 MeterRegistry（ObjectProvider=null）：决策不受影响
        service = new GrayReleaseService(props, null);
    }

    private void rule(String feature, int percent, List<String> allow, List<String> deny) {
        GrayReleaseProperties.Rule r = new GrayReleaseProperties.Rule();
        r.setPercent(percent);
        r.setAllowlist(allow);
        r.setDenylist(deny);
        props.getFeatures().put(feature, r);
    }

    @Test
    void 未配置特性不门控全量放开() {
        assertThat(service.isEnabled("rerank", "business")).isTrue();
        assertThat(service.decide("rerank", "business").reason()).isEqualTo("NOT_CONFIGURED");
    }

    @Test
    void percent0全拒与percent100全放() {
        rule("f-off", 0, List.of(), List.of());
        rule("f-on", 100, List.of(), List.of());
        assertThat(service.isEnabled("f-off", "u1")).isFalse();
        assertThat(service.decide("f-off", "u1").reason()).isEqualTo("PERCENT_0");
        assertThat(service.isEnabled("f-on", "u1")).isTrue();
        assertThat(service.decide("f-on", "u1").reason()).isEqualTo("PERCENT_100");
    }

    @Test
    void 白名单覆盖percent0() {
        rule("f", 0, List.of("admin"), List.of());
        assertThat(service.isEnabled("f", "admin")).isTrue();
        assertThat(service.decide("f", "admin").reason()).isEqualTo("ALLOWLIST");
        assertThat(service.isEnabled("f", "business")).isFalse();
    }

    @Test
    void 黑名单覆盖percent100且优先白名单() {
        rule("f", 100, List.of("bad-actor"), List.of("bad-actor"));
        assertThat(service.isEnabled("f", "bad-actor")).isFalse();
        assertThat(service.decide("f", "bad-actor").reason()).isEqualTo("DENYLIST");
    }

    @Test
    void 分桶稳定且reason携带桶号() {
        rule("rerank", 50, List.of(), List.of());
        boolean first = service.isEnabled("rerank", "user-42");
        for (int i = 0; i < 20; i++) {
            assertThat(service.isEnabled("rerank", "user-42")).isEqualTo(first);
        }
        assertThat(service.decide("rerank", "user-42").reason()).startsWith("BUCKET_");
    }

    @Test
    void 分桶分布大体均匀() {
        rule("rerank", 50, List.of(), List.of());
        int hit = 0;
        int total = 2000;
        for (int i = 0; i < total; i++) {
            if (service.isEnabled("rerank", "user-" + i)) {
                hit++;
            }
        }
        // 50% 放量：允许 35%-65% 波动（哈希分桶非精确等分，但须大体均匀）
        assertThat(hit).isBetween(700, 1300);
    }

    @Test
    void 空白键归入anonymous桶且稳定() {
        rule("rerank", 50, List.of(), List.of());
        boolean a = service.isEnabled("rerank", null);
        boolean b = service.isEnabled("rerank", "  ");
        assertThat(a).isEqualTo(b);
        assertThat(service.isEnabled("rerank", "anonymous")).isEqualTo(a);
    }

    @Test
    void 空特性名放行并标记非法() {
        GrayReleaseService.Decision d = service.decide(" ", "u1");
        assertThat(d.enabled()).isTrue();
        assertThat(d.reason()).isEqualTo("INVALID_FEATURE");
    }

    @Test
    void bucket静态确定性() {
        assertThat(GrayReleaseService.bucket("rerank", "admin"))
                .isEqualTo(GrayReleaseService.bucket("rerank", "admin"))
                .isBetween(0, 99);
    }
}
