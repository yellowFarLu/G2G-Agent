package com.wikiagent.infrastructure.tool;

import com.wikiagent.domain.memory.HandoverRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * v1-v2 §2 工具：读取交接清单。
 * <p>
 * 封装 {@link HandoverRepository}，读取当前会话的交接清单 JSON。
 * 交接清单包含四段：原始请求 / 已执行节点 / 放弃路径 / 数据引用索引（§4）。
 * <p>
 * 实施校正：@ConditionalOnBean 受扫描顺序影响不可靠，FileHandoverRepository 默认装配。
 * v1-v2 与 v6 PERO 双路径共用（v6 经 {@code PeroToolExecutor} 分派调用）。
 */
@Component
public class ReadHandoverTool {

    private static final Logger log = LoggerFactory.getLogger(ReadHandoverTool.class);

    /** 工具名（与 ToolRegistry 白名单一致）。 */
    public static final String NAME = "read_handover";

    private final HandoverRepository repository;

    public ReadHandoverTool(HandoverRepository repository) {
        this.repository = repository;
    }

    /**
     * 读取当前会话的交接清单 JSON。
     *
     * @param userId    用户 id
     * @param sessionId 会话 id
     * @return 交接清单 JSON 字符串；不存在时返回提示
     */
    public String execute(String userId, String sessionId) {
        if (userId == null || userId.isBlank() || sessionId == null || sessionId.isBlank()) {
            return "[read_handover] userId/sessionId 为空";
        }
        try {
            String json = repository.load(userId, sessionId);
            if (json == null || json.isBlank()) {
                return "[read_handover] 当前会话无交接清单";
            }
            return json;
        } catch (Exception e) {
            log.warn("交接清单读取失败 userId={} sessionId={} err={}",
                    userId, sessionId, e.getMessage());
            return "[read_handover] 读取失败: " + e.getMessage();
        }
    }
}
