package com.wikiagent.infrastructure.tool;

import com.wikiagent.domain.memory.HandoverRepository;
import com.wikiagent.service.agent.JsonExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * v1-v2 §2 工具：列出放弃的路径。
 * <p>
 * 从交接清单中解析 abandonedPaths 段，供 Agent 在复盘时参考被放弃的执行路径。
 * 放弃路径记录了被放弃的节点与原因（§4）。
 * <p>
 * 实施校正：@ConditionalOnBean 受扫描顺序影响不可靠，FileHandoverRepository 默认装配，
 * 故只保留 v1-v2 总开关 @ConditionalOnProperty。
 */
@Component
@ConditionalOnProperty(name = "wikiagent.pero.enabled", havingValue = "false")
public class ListAbandonedPathTool {

    private static final Logger log = LoggerFactory.getLogger(ListAbandonedPathTool.class);

    /** 工具名（与 ToolRegistry 白名单一致）。 */
    public static final String NAME = "list_abandoned_paths";

    private final HandoverRepository repository;

    public ListAbandonedPathTool(HandoverRepository repository) {
        this.repository = repository;
    }

    /**
     * 列出当前会话交接清单中被放弃的路径。
     *
     * @param userId    用户 id
     * @param sessionId 会话 id
     * @return 放弃路径列表文本；无则返回提示
     */
    public String execute(String userId, String sessionId) {
        if (userId == null || userId.isBlank() || sessionId == null || sessionId.isBlank()) {
            return "[list_abandoned_paths] userId/sessionId 为空";
        }
        try {
            String json = repository.load(userId, sessionId);
            if (json == null || json.isBlank()) {
                return "[list_abandoned_paths] 当前会话无交接清单";
            }
            return extractAbandonedPaths(json);
        } catch (Exception e) {
            log.warn("放弃路径读取失败 userId={} sessionId={} err={}",
                    userId, sessionId, e.getMessage());
            return "[list_abandoned_paths] 读取失败: " + e.getMessage();
        }
    }

    /** 从交接清单 JSON 中解析 abandonedPaths 段。 */
    private String extractAbandonedPaths(String json) {
        Map<String, Object> handover = JsonExtractor.parseObject(json);
        if (handover.isEmpty()) {
            // JSON 解析失败，直接返回原始文本
            return json;
        }
        Object raw = handover.get("abandonedPaths");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return "[list_abandoned_paths] 当前会话无放弃路径";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("放弃路径：\n");
        int idx = 1;
        for (Object o : list) {
            sb.append("[").append(idx).append("] ").append(String.valueOf(o)).append("\n");
            idx++;
        }
        return sb.toString();
    }
}
