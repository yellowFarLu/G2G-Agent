package com.wikiagent.infrastructure.memory.mysql;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 5 文件迁移 Runner 测试：合法 todo.json 入库、损坏文件跳过、重复执行幂等。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "wikiagent.memory.handover-adapter=mysql",
        "wikiagent.memory.file-migration.enabled=true"
})
class HandoverFileMigrationRunnerTest {

    static Path tempDir;

    @DynamicPropertySource
    static void handoverDir(DynamicPropertyRegistry registry) throws IOException {
        tempDir = Files.createTempDirectory("handover-migration-");
        registry.add("wikiagent.memory.handover-dir", () -> tempDir.toString());
    }

    @Autowired
    private com.wikiagent.domain.memory.HandoverRepository repository;

    @Autowired
    private HandoverFileMigrationRunner runner;

    private String validTodoJson() {
        return """
                {
                  "originalRequest": "迁移来源请求",
                  "executedNodes": [{"nodeId": "n1", "description": "已完成查询"}],
                  "abandonedPaths": [{"nodeId": "n2", "reason": "路径放弃"}],
                  "dataReferences": [{"key": "k1", "value": "v1"}]
                }
                """;
    }

    @Test
    void migratesValidSkipsCorruptAndIsIdempotent() throws IOException {
        String user = "mig-" + UUID.randomUUID().toString().substring(0, 8);
        String session = "sess-" + UUID.randomUUID().toString().substring(0, 8);

        Path userDir = tempDir.resolve(user).resolve(session);
        Files.createDirectories(userDir);
        Files.writeString(userDir.resolve("todo.json"), validTodoJson());

        String corruptUser = "corrupt-" + UUID.randomUUID().toString().substring(0, 8);
        Path corruptDir = tempDir.resolve(corruptUser).resolve(session);
        Files.createDirectories(corruptDir);
        Files.writeString(corruptDir.resolve("todo.json"), "{坏json");

        runner.migrateAll();

        String json = repository.load(user, session);
        assertThat(json).isNotNull();
        assertThat(json).contains("迁移来源请求").contains("已完成查询").contains("路径放弃");
        assertThat(repository.load(corruptUser, session)).isNull();

        // 幂等：再跑一遍不重复（executedNodes 仍 1 条）
        runner.migrateAll();
        String jsonAgain = repository.load(user, session);
        try {
            com.fasterxml.jackson.databind.JsonNode parsed = new com.fasterxml.jackson.databind.ObjectMapper().readTree(jsonAgain);
            assertThat(parsed.get("executedNodes")).hasSize(1);
            assertThat(parsed.get("abandonedPaths")).hasSize(1);
            assertThat(parsed.get("dataReferences")).hasSize(1);
        } catch (Exception e) {
            throw new IllegalStateException("迁移后 JSON 不可解析", e);
        }
    }
}
