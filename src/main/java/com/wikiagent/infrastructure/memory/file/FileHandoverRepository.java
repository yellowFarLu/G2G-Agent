package com.wikiagent.infrastructure.memory.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wikiagent.domain.memory.HandoverRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * v1-v2 §4 交接清单端口 - 文件实现适配器。
 * <p>
 * 落盘路径：{@code {handover-dir}/{userId}/{sessionId}/todo.json}
 * <p>
 * JSON 结构 4 段（用户硬性要求）：
 * <ul>
 *   <li>originalRequest: 用户原始请求</li>
 *   <li>executedNodes: 已执行节点列表 [{nodeId, description}]</li>
 *   <li>abandonedPaths: 放弃路径列表 [{nodeId, reason}]</li>
 *   <li>dataReferences: 数据引用 [{key, value}]</li>
 * </ul>
 * <p>
 * 线程安全：所有读写操作 synchronized，避免并发下文件覆盖。
 * Python 脚本失败不影响主流程（仅告警）。
 */
@Service
@ConditionalOnProperty(name = "wikiagent.memory.handover-adapter", havingValue = "file")
public class FileHandoverRepository implements HandoverRepository {

    private static final Logger log = LoggerFactory.getLogger(FileHandoverRepository.class);

    private final ObjectMapper mapper;
    private final String handoverDir;
    private final String pythonScript;

    public FileHandoverRepository(@Value("${wikiagent.memory.handover-dir:./data/handover}") String handoverDir,
                                  @Value("${wikiagent.memory.python-script:scripts/extract_field_index.py}") String pythonScript) {
        this.mapper = new ObjectMapper();
        this.handoverDir = handoverDir;
        this.pythonScript = pythonScript;
    }

    @Override
    public synchronized void init(String userId, String sessionId, String originalRequest) {
        ObjectNode root = mapper.createObjectNode();
        root.put("originalRequest", originalRequest == null ? "" : originalRequest);
        root.set("executedNodes", mapper.createArrayNode());
        root.set("abandonedPaths", mapper.createArrayNode());
        root.set("dataReferences", mapper.createArrayNode());
        write(userId, sessionId, root);
        log.debug("交接清单已初始化 userId={} sessionId={}", userId, sessionId);
    }

    @Override
    public synchronized void addExecutedNode(String userId, String sessionId, String nodeId, String description) {
        ObjectNode root = readOrCreate(userId, sessionId);
        ArrayNode arr = withArray(root, "executedNodes");
        ObjectNode item = mapper.createObjectNode();
        item.put("nodeId", nodeId == null ? "" : nodeId);
        item.put("description", description == null ? "" : description);
        arr.add(item);
        write(userId, sessionId, root);
    }

    @Override
    public synchronized void addAbandonedPath(String userId, String sessionId, String nodeId, String reason) {
        ObjectNode root = readOrCreate(userId, sessionId);
        ArrayNode arr = withArray(root, "abandonedPaths");
        ObjectNode item = mapper.createObjectNode();
        item.put("nodeId", nodeId == null ? "" : nodeId);
        item.put("reason", reason == null ? "" : reason);
        arr.add(item);
        write(userId, sessionId, root);
    }

    @Override
    public synchronized void addDataReference(String userId, String sessionId, String key, String value) {
        ObjectNode root = readOrCreate(userId, sessionId);
        ArrayNode arr = withArray(root, "dataReferences");
        ObjectNode item = mapper.createObjectNode();
        item.put("key", key == null ? "" : key);
        item.put("value", value == null ? "" : value);
        arr.add(item);
        write(userId, sessionId, root);
    }

    @Override
    public synchronized String load(String userId, String sessionId) {
        Path path = todoPath(userId, sessionId);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("交接清单读取失败 path={}: {}", path, e.getMessage());
            return null;
        }
    }

    @Override
    public void refreshFieldIndex(String userId, String sessionId) {
        // 调用 Python 脚本刷新 field_index.json；Python 不存在或失败不阻断主流程
        try {
            Path dir = handoverPath(userId, sessionId);
            if (!Files.exists(dir)) {
                return;
            }
            String script = pythonScript;
            ProcessBuilder pb = new ProcessBuilder("python3", script,
                    "--dir", dir.toString(),
                    "--sessionId", sessionId);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    log.debug("field_index 脚本输出: {}", line);
                }
            }
            int code = p.waitFor();
            if (code != 0) {
                log.warn("field_index 脚本退出码 {} (sessionId={})", code, sessionId);
            }
        } catch (IOException | InterruptedException e) {
            log.warn("field_index 脚本调用失败 sessionId={}: {}", sessionId, e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 读取现有 todo.json；不存在则返回空骨架。 */
    private ObjectNode readOrCreate(String userId, String sessionId) {
        Path path = todoPath(userId, sessionId);
        if (Files.exists(path)) {
            try {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                JsonNode parsed = mapper.readTree(content);
                if (parsed instanceof ObjectNode obj) {
                    return obj;
                }
            } catch (IOException e) {
                log.warn("交接清单解析失败 path={}：将重建", path);
            }
        }
        ObjectNode root = mapper.createObjectNode();
        root.put("originalRequest", "");
        root.set("executedNodes", mapper.createArrayNode());
        root.set("abandonedPaths", mapper.createArrayNode());
        root.set("dataReferences", mapper.createArrayNode());
        return root;
    }

    /** 取 root 中某数组字段，缺失则创建。 */
    private ArrayNode withArray(ObjectNode root, String field) {
        JsonNode node = root.get(field);
        if (node instanceof ArrayNode arr) {
            return arr;
        }
        ArrayNode arr = mapper.createArrayNode();
        root.set(field, arr);
        return arr;
    }

    /** 序列化并落盘 todo.json。 */
    private void write(String userId, String sessionId, ObjectNode root) {
        Path path = todoPath(userId, sessionId);
        try {
            Files.createDirectories(path.getParent());
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            Files.writeString(path, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("交接清单写入失败 path={}: {}", path, e.getMessage());
        }
    }

    private Path handoverPath(String userId, String sessionId) {
        return Paths.get(handoverDir, userId, sessionId);
    }

    private Path todoPath(String userId, String sessionId) {
        return handoverPath(userId, sessionId).resolve("todo.json");
    }

    // 以下工具方法仅供单元测试断言 JSON 结构用，非对外 API（避免被 Lint 误删）
    List<Map<String, String>> readExecutedNodes(String userId, String sessionId) {
        return readArray(userId, sessionId, "executedNodes");
    }

    List<Map<String, String>> readAbandonedPaths(String userId, String sessionId) {
        return readArray(userId, sessionId, "abandonedPaths");
    }

    List<Map<String, String>> readDataReferences(String userId, String sessionId) {
        return readArray(userId, sessionId, "dataReferences");
    }

    private List<Map<String, String>> readArray(String userId, String sessionId, String field) {
        List<Map<String, String>> out = new ArrayList<>();
        ObjectNode root = readOrCreate(userId, sessionId);
        JsonNode arr = root.get(field);
        if (arr instanceof ArrayNode arrayNode) {
            Iterator<JsonNode> it = arrayNode.elements();
            while (it.hasNext()) {
                JsonNode item = it.next();
                if (item instanceof ObjectNode obj) {
                    Map<String, String> m = new java.util.HashMap<>();
                    obj.fields().forEachRemaining(e -> m.put(e.getKey(),
                            e.getValue() == null ? "" : e.getValue().asText()));
                    out.add(m);
                }
            }
        }
        return out;
    }
}
