package com.wikiagent.application.extract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B4 schema 注册表：classpath JSON 加载（发票示例域）。
 */
class ExtractionSchemaRegistryTest {

    @Test
    void loadsInvoiceExampleSchemaFromClasspath() {
        ExtractionSchemaRegistry registry = new ExtractionSchemaRegistry();
        registry.load();

        assertThat(registry.keys()).contains("invoice-demo");
        var schema = registry.get("invoice-demo").orElseThrow();
        assertThat(schema.version()).isEqualTo("1.0");
        assertThat(schema.fields()).hasSize(6);
        var invoiceNo = schema.field("invoiceNo").orElseThrow();
        assertThat(invoiceNo.required()).isTrue();
        assertThat(invoiceNo.pattern()).isEqualTo("^[0-9A-Za-z]{6,20}$");
        assertThat(schema.field("currency").orElseThrow().enumValues())
                .containsExactly("CNY", "USD", "EUR");
        assertThat(registry.get("not-exists")).isEmpty();
        assertThat(registry.get(null)).isEmpty();
    }
}
