package com.wikiagent.infrastructure.memory.mysql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * Task 5 存量 todo.json → MySQL 一次性迁移 Runner（规格 2.5）。
 * <p>
 * 开关 {@code wikiagent.memory.file-migration.enabled=true} 时随应用启动执行一次，
 * 也可显式调用 {@link #migrateAll()}。幂等策略：checklist 已存在则整文件跳过；
 * 损坏 todo.json 告警跳过，不阻断其他文件。迁移期间 Python 脚本与 field_index.json 不再参与。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.memory.file-migration.enabled", havingValue = "true")
public class HandoverFileMigrationRunner implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(HandoverFileMigrationRunner.class);

    private final com.wikiagent.domain.memory.HandoverRepository repository;
    private final HandoverChecklistJpaDao checklistDao;
    private final ObjectMapper mapper;
    private final String handoverDir;

    public HandoverFileMigrationRunner(com.wikiagent.domain.memory.HandoverRepository repository,
                                       HandoverChecklistJpaDao checklistDao,
                                       @Value("${wikiagent.memory.handover-dir:./data/handover}") String handoverDir) {
        this.repository = repository;
        this.checklistDao = checklistDao;
        this.mapper = new ObjectMapper();
        this.handoverDir = handoverDir;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        migrateAll();
    }

    /** 扫描 {handover-dir}/{userId}/{sessionId}/todo.json 并入库；损坏跳过、已迁移跳过。 */
    public void migrateAll() {
        Path root = Paths.get(handoverDir);
        if (!Files.isDirectory(root)) {
            log.info("交接清单迁移跳过：目录不存在 {}", root);
            return;
        }
        int migrated = 0;
        int skipped = 0;
        int corrupt = 0;
        try (Stream<Path> users = Files.list(root)) {
            for (Path userDir : users.filter(Files::isDirectory).toList()) {
                try (Stream<Path> sessions = Files.list(userDir)) {
                    for (Path sessionDir : sessions.filter(Files::isDirectory).toList()) {
                        Path todo = sessionDir.resolve("todo.json");
                        if (!Files.isRegularFile(todo)) {
                            continue;
                        }
                        String userId = userDir.getFileName().toString();
                        String sessionId = sessionDir.getFileName().toString();
                        try {
                            if (checklistDao.findByUserIdAndSessionId(userId, sessionId).isPresent()) {
                                skipped++;
                                continue;
                            }
                            if (migrateOne(userId, sessionId, todo)) {
                                migrated++;
                            } else {
                                skipped++;
                            }
                        } catch (Exception e) {
                            corrupt++;
                            log.warn("交接清单迁移失败（跳过）userId={} sessionId={} path={}: {}",
                                    userId, sessionId, todo, e.getMessage());
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("交接清单迁移目录遍历失败 {}: {}", root, e.getMessage());
            return;
        }
        log.info("交接清单迁移完成 migrated={} skippedExisting={} corruptOrEmpty={}", migrated, skipped, corrupt);
    }

    /** 迁移单个 todo.json；解析失败返回 false（计入跳过）。 */
    private boolean migrateOne(String userId, String sessionId, Path todo) throws IOException {
        JsonNode root;
        try {
            root = mapper.readTree(Files.readString(todo));
        } catch (Exception e) {
            log.warn("todo.json 损坏，跳过迁移 path={}: {}", todo, e.getMessage());
            return false;
        }
        if (root == null || !root.isObject()) {
            log.warn("todo.json 结构非法，跳过迁移 path={}", todo);
            return false;
        }
        repository.init(userId, sessionId, textOrEmpty(root, "originalRequest"));
        JsonNode executed = root.get("executedNodes");
        if (executed != null && executed.isArray()) {
            for (JsonNode item : executed) {
                repository.addExecutedNode(userId, sessionId,
                        textOrEmpty(item, "nodeId"), textOrEmpty(item, "description"));
            }
        }
        JsonNode abandoned = root.get("abandonedPaths");
        if (abandoned != null && abandoned.isArray()) {
            for (JsonNode item : abandoned) {
                repository.addAbandonedPath(userId, sessionId,
                        textOrEmpty(item, "nodeId"), textOrEmpty(item, "reason"));
            }
        }
        JsonNode refs = root.get("dataReferences");
        if (refs != null && refs.isArray()) {
            for (JsonNode item : refs) {
                repository.addDataReference(userId, sessionId,
                        textOrEmpty(item, "key"), textOrEmpty(item, "value"));
            }
        }
        return true;
    }

    private String textOrEmpty(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }
}
