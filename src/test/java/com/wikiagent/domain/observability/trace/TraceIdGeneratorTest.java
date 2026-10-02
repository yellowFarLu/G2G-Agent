package com.wikiagent.domain.observability.trace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-I1 {@link TraceIdGenerator} 单测：格式、唯一、头值透传/空白回退。
 */
class TraceIdGeneratorTest {

    @Test
    void 生成的traceId带tr前缀且长度合理() {
        String t = TraceIdGenerator.generate();
        assertThat(t).startsWith("tr-").hasSizeGreaterThan(8).hasSizeLessThan(64);
    }

    @Test
    void 连续生成不重复() {
        String a = TraceIdGenerator.generate();
        String b = TraceIdGenerator.generate();
        assertThat(a).isNotEqualTo(b);
        // 末段为 8 位十六进制
        String suffix = a.substring(a.lastIndexOf('-') + 1);
        assertThat(suffix).matches("[0-9a-f]{8}");
    }

    @Test
    void resolve非空白头值原样透传并trim() {
        assertThat(TraceIdGenerator.resolve("  upstream-trace-1 ")).isEqualTo("upstream-trace-1");
    }

    @Test
    void resolve空值null空白均生成新traceId() {
        assertThat(TraceIdGenerator.resolve(null)).startsWith("tr-");
        assertThat(TraceIdGenerator.resolve("")).startsWith("tr-");
        assertThat(TraceIdGenerator.resolve("   ")).startsWith("tr-");
    }
}
