package com.wikiagent.infrastructure.tool;

import com.wikiagent.domain.memory.HistoricalEvent;
import com.wikiagent.domain.memory.HistoricalEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * v1-v2 §2 工具：历史事件检索。
 * <p>
 * 封装 {@link HistoricalEventRepository}，按语义相似度检索过去对话历史，
 * 帮助 Agent 在跨会话场景下复用历史结论（Reflexion 论文的 episodic memory 思想）。
 * <p>
 * 实施校正：原 @ConditionalOnBean(HistoricalEventRepository.class) 受组件扫描顺序
 * 影响不可靠；端口实现 MilvusHistoricalEventRepository 默认装配（matchIfMissing）。
 * v1-v2 与 v6 PERO 双路径共用（v6 经 {@code PeroToolExecutor} 分派调用）。
 */
@Component
public class SearchHistoryTool {

    private static final Logger log = LoggerFactory.getLogger(SearchHistoryTool.class);

    /** 工具名（与 ToolRegistry 白名单一致）。 */
    public static final String NAME = "search_history";

    /** 默认召回数量。 */
    private static final int DEFAULT_TOP_K = 5;

    private final HistoricalEventRepository repository;

    public SearchHistoryTool(HistoricalEventRepository repository) {
        this.repository = repository;
    }

    /**
     * 按语义相似度检索历史事件。
     *
     * @param query 检索查询
     * @param topK  召回数量上限
     * @return 历史事件列表格式化文本；无结果时返回提示
     */
    public String execute(String query, int topK) {
        if (query == null || query.isBlank()) {
            return "[search_history] 查询为空，未执行检索";
        }
        int limit = topK > 0 ? topK : DEFAULT_TOP_K;
        try {
            List<HistoricalEvent> events = repository.search(query, limit);
            if (events == null || events.isEmpty()) {
                return "[search_history] 未检索到相关历史事件";
            }
            return formatEvents(events);
        } catch (Exception e) {
            log.warn("历史事件检索失败 query={} err={}", query, e.getMessage());
            return "[search_history] 检索失败: " + e.getMessage();
        }
    }

    /** 将历史事件列表格式化为文本。 */
    private String formatEvents(List<HistoricalEvent> events) {
        StringBuilder sb = new StringBuilder();
        sb.append("历史事件：\n");
        int idx = 1;
        for (HistoricalEvent ev : events) {
            sb.append("[").append(idx).append("] ")
                    .append("type=").append(ev.eventType())
                    .append(" createdAt=").append(ev.createdAt())
                    .append("\n    ").append(truncate(ev.content(), 500))
                    .append("\n");
            idx++;
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
