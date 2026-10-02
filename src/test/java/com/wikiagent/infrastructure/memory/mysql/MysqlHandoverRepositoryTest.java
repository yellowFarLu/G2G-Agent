package com.wikiagent.infrastructure.memory.mysql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 5 MySQL 交接清单适配器测试：四段 JSON 结构与 File 版逐字同构。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "wikiagent.memory.handover-adapter=mysql",
        "wikiagent.memory.file-migration.enabled=false"
})
class MysqlHandoverRepositoryTest {

    private static final String USER = "u-" + Long.toHexString(System.nanoTime());
    private static final String SESSION = "s-" + Long.toHexString(System.nanoTime());

    @Autowired
    private com.wikiagent.domain.memory.HandoverRepository repository;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void initThenAccumulateThenLoadSameShapeAsFile() {
        repository.init(USER, SESSION, "帮我查订单并退款");
        repository.addExecutedNode(USER, SESSION, "node-1", "已查询订单状态");
        repository.addAbandonedPath(USER, SESSION, "node-2", "库存不足，放弃换货路径");
        repository.addDataReference(USER, SESSION, "order_id", "SO123456");

        String json = repository.load(USER, SESSION);
        assertThat(json).isNotNull();
        try {
            JsonNode parsed = mapper.readTree(json);
            assertThat(parsed.has("originalRequest")).isTrue();
            assertThat(parsed.get("originalRequest").asText()).isEqualTo("帮我查订单并退款");
            assertThat(parsed.get("executedNodes")).hasSize(1);
            assertThat(parsed.get("executedNodes").get(0).get("nodeId").asText()).isEqualTo("node-1");
            assertThat(parsed.get("executedNodes").get(0).get("description").asText()).isEqualTo("已查询订单状态");
            assertThat(parsed.get("abandonedPaths")).hasSize(1);
            assertThat(parsed.get("abandonedPaths").get(0).get("nodeId").asText()).isEqualTo("node-2");
            assertThat(parsed.get("abandonedPaths").get(0).get("reason").asText()).isEqualTo("库存不足，放弃换货路径");
            assertThat(parsed.get("dataReferences")).hasSize(1);
            assertThat(parsed.get("dataReferences").get(0).get("key").asText()).isEqualTo("order_id");
            assertThat(parsed.get("dataReferences").get(0).get("value").asText()).isEqualTo("SO123456");
        } catch (Exception e) {
            throw new IllegalStateException("load 返回的 JSON 不可解析", e);
        }
    }

    @Test
    void repeatedInitDoesNotDuplicateChecklist() {
        repository.init(USER + "-dup", SESSION + "-dup", "第一问");
        repository.addExecutedNode(USER + "-dup", SESSION + "-dup", "n1", "执行1");
        repository.init(USER + "-dup", SESSION + "-dup", "第一问");
        String json = repository.load(USER + "-dup", SESSION + "-dup");
        assertThat(json).isNotNull();
        try {
            assertThat(mapper.readTree(json).get("executedNodes")).hasSize(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void loadMissingReturnsNull() {
        assertThat(repository.load("nobody-" + uid(), "nosession-" + uid())).isNull();
    }

    private static String uid() {
        return Long.toHexString(System.nanoTime());
    }
}
