package com.wikiagent.infrastructure.memory.mysql;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 5 确定性占位符解析测试（替代 Python field_index）。
 */
class DataRefValueResolverTest {

    private final DataRefValueResolver resolver = new DataRefValueResolver();

    @Test
    void resolvesPresentKeysAndKeepsMissingPlaceholders() {
        Map<String, String> values = Map.of("order_id", "SO123456", "refund_amount", "199.00");
        String text = "订单 [DATA:order_id] 金额 [DATA:refund_amount] 状态 [DATA:order_status]";
        String out = resolver.replacePlaceholders(text, values);
        assertThat(out).isEqualTo("订单 SO123456 金额 199.00 状态 [DATA:order_status]");
    }

    @Test
    void resolveExtractsDataReferenceMapFromChecklistJson() {
        String checklistJson = """
                {
                  "originalRequest": "查询订单",
                  "executedNodes": [{"nodeId": "n1", "description": "d"}],
                  "abandonedPaths": [],
                  "dataReferences": [
                    {"key": "order_id", "value": "SO1"},
                    {"key": "amount", "value": "99"}
                  ]
                }
                """;
        Map<String, String> values = resolver.resolve(checklistJson);
        assertThat(values).containsEntry("order_id", "SO1").containsEntry("amount", "99");
    }

    @Test
    void resolveOnInvalidJsonReturnsEmptyMap() {
        assertThat(resolver.resolve("{坏json")).isEmpty();
    }
}
